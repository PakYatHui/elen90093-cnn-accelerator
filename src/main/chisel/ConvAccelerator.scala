package myaccelerators

import chisel3._
import chisel3.util._

import org.chipsalliance.cde.config._
import org.chipsalliance.diplomacy.lazymodule._
import freechips.rocketchip.tile._
import freechips.rocketchip.rocket._

// =============================================================================
// Phase 1 direct replacement accelerator.
// Fixed16-only minimal 4-output tile path for correctness validation.
// Interface remains CONFIG -> KERNEL -> DATA -> COMPUTE -> STORE.
// =============================================================================

class MyConvAccel(opcodes: OpcodeSet)(implicit p: Parameters) extends LazyRoCC(opcodes) {
  println("DEBUG: Elaborating MyConvAccel Phase 1 fixed16 direct 4-output tile simple version")
  override lazy val module = new MyConvAccelModule(this)
}

class MyConvAccelModule(outer: MyConvAccel)(implicit p: Parameters)
  extends LazyRoCCModuleImp(outer)
  with HasCoreParameters {

  // ---------------------------------------------------------------------------
  // External RoCC command interface
  // ---------------------------------------------------------------------------
  val cmd = Queue(io.cmd, 1)

  // ---------------------------------------------------------------------------
  // Fixed design parameters
  // ---------------------------------------------------------------------------
  val INPUT_SIZE       = 32
  val INPUT_ELEMS      = INPUT_SIZE * INPUT_SIZE
  val MAX_KERNEL_SIZE  = 5
  val MAX_KERNEL_ELEMS = MAX_KERNEL_SIZE * MAX_KERNEL_SIZE

  // Return values
  val RET_ERROR   = 0.U(xLen.W)
  val RET_SUCCESS = 1.U(xLen.W)

  // Data type encoding
  val DATA_FIXED16 = 0.U(2.W)
  val DATA_FLOAT16 = 1.U(2.W)

  // funct7 encoding
  val FUNCT_CONFIG  = 0.U(7.W)
  val FUNCT_DATA    = 1.U(7.W)
  val FUNCT_COMPUTE = 2.U(7.W)
  val FUNCT_STORE   = 3.U(7.W)
  val FUNCT_KERNEL  = 4.U(7.W)

  // Kernel/bias preload type
  val PRELOAD_KERNEL = 0.U(1.W)
  val PRELOAD_BIAS   = 1.U(1.W)

  // FSM states
  val sIdle :: sDecode :: sConfig :: sKernel :: sLoad :: sCompute :: sStore :: sRespond :: Nil = Enum(8)
  val state = RegInit(sIdle)

  // Fixed16 tile engine states
  val ftIdle :: ftLoad :: ftLoadWait :: ftStore :: ftStoreWait :: ftDone :: Nil = Enum(6)
  val fixedTileState = RegInit(ftIdle)

  // ---------------------------------------------------------------------------
  // Latched command fields
  // ---------------------------------------------------------------------------
  val functReg = RegInit(0.U(7.W))
  val rs1Reg   = RegInit(0.U(xLen.W))
  val rs2Reg   = RegInit(0.U(xLen.W))
  val rdReg    = RegInit(0.U(5.W))
  val dprvReg  = RegInit(0.U(2.W))
  val xdReg    = RegInit(false.B)

  // ---------------------------------------------------------------------------
  // Runtime configuration registers
  // ---------------------------------------------------------------------------
  val inputAddrReg   = RegInit(0.U(xLen.W))
  val kernelAddrReg  = RegInit(0.U(xLen.W))
  val outputAddrReg  = RegInit(0.U(xLen.W))
  val biasAddrReg    = RegInit(0.U(xLen.W))
  val kernelSizeReg  = RegInit(0.U(3.W))
  val kernelElemsReg = RegInit(0.U(6.W))
  val dataTypeReg    = RegInit(DATA_FIXED16)
  val biasEnableReg  = RegInit(false.B)

  // Command sequence protection flags
  val configuredReg       = RegInit(false.B)
  val kernelConfiguredReg = RegInit(false.B)
  val loadedReg           = RegInit(false.B)
  val computedReg         = RegInit(false.B)

  // Response register
  val resultReg = RegInit(RET_ERROR)

  // ---------------------------------------------------------------------------
  // Kernel and bias storage
  // ---------------------------------------------------------------------------
  val kernelBuf = Reg(Vec(MAX_KERNEL_ELEMS, UInt(16.W)))
  val biasBuf   = RegInit(0.U(16.W))

  // ---------------------------------------------------------------------------
  // Kernel/bias preload bookkeeping
  // ---------------------------------------------------------------------------
  val preloadIdx      = RegInit(0.U(6.W))
  val preloadInflight = RegInit(false.B)
  val preloadType     = RegInit(PRELOAD_KERNEL)

  // ---------------------------------------------------------------------------
  // Phase 1 fixed16 4-output tile engine bookkeeping
  // ---------------------------------------------------------------------------
  // This is intentionally simple and readable:
  //   - scalar 16-bit input loads only;
  //   - four adjacent outputs are accumulated in four accumulators;
  //   - one 64-bit packed store is issued per four outputs.
  val tileBaseIdx = RegInit(0.U(11.W))
  val tileLane    = RegInit(0.U(2.W))
  val tileKr      = RegInit(0.U(3.W))
  val tileKc      = RegInit(0.U(3.W))
  val tileElemIdx = RegInit(0.U(5.W))
  val tileAcc     = RegInit(VecInit(Seq.fill(4)(0.S(40.W))))

  // ---------------------------------------------------------------------------
  // Command decode helper wires
  // ---------------------------------------------------------------------------
  val doConfig  = functReg === FUNCT_CONFIG
  val doData    = functReg === FUNCT_DATA
  val doCompute = functReg === FUNCT_COMPUTE
  val doStore   = functReg === FUNCT_STORE
  val doKernel  = functReg === FUNCT_KERNEL

  val validKernelSize =
    (rs1Reg === 1.U) || (rs1Reg === 3.U) || (rs1Reg === 5.U)

  // Phase 1 direct version is fixed16-only.
  val validDataType = rs2Reg(1, 0) === DATA_FIXED16

  // ---------------------------------------------------------------------------
  // Default IO assignments
  // ---------------------------------------------------------------------------
  cmd.ready := false.B

  io.resp.valid := false.B
  io.resp.bits.rd := rdReg
  io.resp.bits.data := resultReg

  io.busy := state =/= sIdle
  io.interrupt := false.B

  io.mem.req.valid := false.B
  io.mem.req.bits.addr := 0.U
  io.mem.req.bits.tag := 0.U
  io.mem.req.bits.cmd := M_XRD
  io.mem.req.bits.size := 1.U
  io.mem.req.bits.phys := false.B
  io.mem.req.bits.signed := true.B
  io.mem.req.bits.data := 0.U
  io.mem.req.bits.dprv := dprvReg

  // ---------------------------------------------------------------------------
  // Fixed16 tile address generation
  // ---------------------------------------------------------------------------
  val tileRadius  = kernelSizeReg >> 1
  val tileBaseRow = tileBaseIdx(9, 5)
  val tileBaseCol = tileBaseIdx(4, 0)

  val tileInRowS =
    tileBaseRow.zext + tileKr.zext - tileRadius.zext

  val tileInColS =
    tileBaseCol.zext + tileLane.zext + tileKc.zext - tileRadius.zext

  val tileInputValid =
    tileInRowS >= 0.S && tileInRowS < INPUT_SIZE.S &&
    tileInColS >= 0.S && tileInColS < INPUT_SIZE.S

  val tileMemIndex = (tileInRowS.asUInt << 5) + tileInColS.asUInt
  val tileMemAddr  = inputAddrReg + (tileMemIndex << 1)

  val biasTerm = Wire(SInt(40.W))
  biasTerm := Mux(biasEnableReg, biasBuf.asSInt, 0.S(40.W))

  val tileOut0 = (tileAcc(0) + biasTerm).asUInt(15, 0)
  val tileOut1 = (tileAcc(1) + biasTerm).asUInt(15, 0)
  val tileOut2 = (tileAcc(2) + biasTerm).asUInt(15, 0)
  val tileOut3 = (tileAcc(3) + biasTerm).asUInt(15, 0)

  val tileStoreData = Cat(tileOut3, tileOut2, tileOut1, tileOut0)

  // ---------------------------------------------------------------------------
  // Helper methods
  // ---------------------------------------------------------------------------
  def resetTileCursors(): Unit = {
    tileLane := 0.U
    tileKr := 0.U
    tileKc := 0.U
    tileElemIdx := 0.U
  }

  def resetTileAccumulators(): Unit = {
    for (i <- 0 until 4) {
      tileAcc(i) := 0.S
    }
  }

  def advanceTileCursor(): Unit = {
    val nextKc = tileKc + 1.U
    tileElemIdx := tileElemIdx + 1.U

    when(nextKc >= kernelSizeReg) {
      tileKc := 0.U
      tileKr := tileKr + 1.U
    }.otherwise {
      tileKc := nextKc
    }
  }

  def advanceTileLaneOrStore(): Unit = {
    when(tileLane === 3.U) {
      fixedTileState := ftStore
    }.otherwise {
      tileLane := tileLane + 1.U
      tileKr := 0.U
      tileKc := 0.U
      tileElemIdx := 0.U
      fixedTileState := ftLoad
    }
  }

  // ---------------------------------------------------------------------------
  // Main FSM
  // ---------------------------------------------------------------------------
  switch(state) {

    is(sIdle) {
      cmd.ready := true.B

      when(cmd.fire) {
        functReg := cmd.bits.inst.funct
        rs1Reg   := cmd.bits.rs1
        rs2Reg   := cmd.bits.rs2
        rdReg    := cmd.bits.inst.rd
        dprvReg  := cmd.bits.status.dprv
        xdReg    := cmd.bits.inst.xd
        state    := sDecode
      }
    }

    is(sDecode) {
      when(doConfig) {
        state := sConfig

      }.elsewhen(doKernel) {
        when(configuredReg) {
          state := sKernel
        }.otherwise {
          resultReg := RET_ERROR
          state := sRespond
        }

      }.elsewhen(doData) {
        when(configuredReg && kernelConfiguredReg) {
          inputAddrReg := rs1Reg
          outputAddrReg := rs2Reg

          preloadIdx := 0.U
          preloadInflight := false.B
          preloadType := PRELOAD_KERNEL

          loadedReg := false.B
          computedReg := false.B

          state := sLoad
        }.otherwise {
          resultReg := RET_ERROR
          state := sRespond
        }

      }.elsewhen(doCompute) {
        when(configuredReg && kernelConfiguredReg && loadedReg && dataTypeReg === DATA_FIXED16) {
          tileBaseIdx := 0.U
          resetTileCursors()
          resetTileAccumulators()
          fixedTileState := ftIdle
          computedReg := false.B
          state := sCompute
        }.otherwise {
          resultReg := RET_ERROR
          state := sRespond
        }

      }.elsewhen(doStore) {
        when(computedReg) {
          resultReg := RET_SUCCESS
        }.otherwise {
          resultReg := RET_ERROR
        }
        state := sRespond

      }.otherwise {
        resultReg := RET_ERROR
        state := sRespond
      }
    }

    is(sConfig) {
      when(validKernelSize && validDataType) {
        kernelSizeReg := rs1Reg(2, 0)
        dataTypeReg := rs2Reg(1, 0)
        biasEnableReg := rs2Reg(2)

        when(rs1Reg === 1.U) {
          kernelElemsReg := 1.U
        }.elsewhen(rs1Reg === 3.U) {
          kernelElemsReg := 9.U
        }.otherwise {
          kernelElemsReg := 25.U
        }

        configuredReg := true.B
        kernelConfiguredReg := false.B
        loadedReg := false.B
        computedReg := false.B
        resultReg := RET_SUCCESS
      }.otherwise {
        configuredReg := false.B
        kernelConfiguredReg := false.B
        loadedReg := false.B
        computedReg := false.B
        kernelSizeReg := 0.U
        kernelElemsReg := 0.U
        dataTypeReg := DATA_FIXED16
        biasEnableReg := false.B
        biasBuf := 0.U
        resultReg := RET_ERROR
      }

      state := sRespond
    }

    is(sKernel) {
      kernelAddrReg := rs1Reg
      biasAddrReg := rs2Reg
      kernelConfiguredReg := true.B
      loadedReg := false.B
      computedReg := false.B
      resultReg := RET_SUCCESS
      state := sRespond
    }

    is(sLoad) {
      // DATA stage preloads only kernel weights and optional scalar bias.
      when(!preloadInflight) {
        when(preloadIdx < kernelElemsReg) {
          io.mem.req.valid := true.B
          io.mem.req.bits.addr := kernelAddrReg + (preloadIdx << 1)
          io.mem.req.bits.tag := 0.U
          io.mem.req.bits.cmd := M_XRD
          io.mem.req.bits.size := 1.U
          io.mem.req.bits.signed := true.B
          io.mem.req.bits.dprv := dprvReg

          when(io.mem.req.fire) {
            preloadInflight := true.B
            preloadType := PRELOAD_KERNEL
          }

        }.elsewhen(biasEnableReg) {
          io.mem.req.valid := true.B
          io.mem.req.bits.addr := biasAddrReg
          io.mem.req.bits.tag := 0.U
          io.mem.req.bits.cmd := M_XRD
          io.mem.req.bits.size := 1.U
          io.mem.req.bits.signed := true.B
          io.mem.req.bits.dprv := dprvReg

          when(io.mem.req.fire) {
            preloadInflight := true.B
            preloadType := PRELOAD_BIAS
          }

        }.otherwise {
          loadedReg := true.B
          computedReg := false.B
          resultReg := RET_SUCCESS
          state := sRespond
        }
      }

      when(io.mem.resp.valid) {
        when(preloadType === PRELOAD_KERNEL) {
          kernelBuf(preloadIdx(4, 0)) := io.mem.resp.bits.data(15, 0)
          preloadIdx := preloadIdx + 1.U
        }.otherwise {
          biasBuf := io.mem.resp.bits.data(15, 0)
          biasEnableReg := false.B
          // Keep the loaded bias value, but prevent repeated bias loads.
          // The original bias-enable semantic is restored by dataType/config tests
          // only through the loaded bias term below.
        }

        preloadInflight := false.B
      }

      // Restore the bias-enable meaning after the one-shot load has completed.
      // This register is not used as a load-progress flag after sLoad.
      when(preloadType === PRELOAD_BIAS && io.mem.resp.valid) {
        biasEnableReg := true.B
        loadedReg := true.B
        computedReg := false.B
        resultReg := RET_SUCCESS
        state := sRespond
      }
    }

    is(sCompute) {
      switch(fixedTileState) {
        is(ftIdle) {
          when(tileBaseIdx >= INPUT_ELEMS.U) {
            computedReg := true.B
            resultReg := RET_SUCCESS
            fixedTileState := ftDone
            state := sRespond
          }.otherwise {
            resetTileCursors()
            resetTileAccumulators()
            fixedTileState := ftLoad
          }
        }

        is(ftLoad) {
          when(tileElemIdx >= kernelElemsReg) {
            advanceTileLaneOrStore()

          }.elsewhen(!tileInputValid) {
            advanceTileCursor()

          }.otherwise {
            io.mem.req.valid := true.B
            io.mem.req.bits.addr := tileMemAddr
            io.mem.req.bits.tag := 0.U
            io.mem.req.bits.cmd := M_XRD
            io.mem.req.bits.size := 1.U
            io.mem.req.bits.signed := true.B
            io.mem.req.bits.dprv := dprvReg

            when(io.mem.req.fire) {
              fixedTileState := ftLoadWait
            }
          }
        }

        is(ftLoadWait) {
          when(io.mem.resp.valid) {
            val inVal = io.mem.resp.bits.data(15, 0).asSInt
            val kerVal = kernelBuf(tileElemIdx).asSInt
            val product = inVal * kerVal
            val term = (product >> 8).asSInt

            tileAcc(tileLane) := (tileAcc(tileLane) + term).asUInt(39, 0).asSInt

            advanceTileCursor()
            fixedTileState := ftLoad
          }
        }

        is(ftStore) {
          io.mem.req.valid := true.B
          io.mem.req.bits.addr := outputAddrReg + (tileBaseIdx << 1)
          io.mem.req.bits.tag := 0.U
          io.mem.req.bits.cmd := M_XWR
          io.mem.req.bits.size := 3.U
          io.mem.req.bits.signed := false.B
          io.mem.req.bits.data := tileStoreData
          io.mem.req.bits.dprv := dprvReg

          when(io.mem.req.fire) {
            fixedTileState := ftStoreWait
          }
        }

        is(ftStoreWait) {
          when(io.mem.resp.valid) {
            tileBaseIdx := tileBaseIdx + 4.U
            fixedTileState := ftIdle
          }
        }

        is(ftDone) {
          computedReg := true.B
          resultReg := RET_SUCCESS
          state := sRespond
        }
      }
    }

    is(sStore) {
      // Compatibility barrier.
      // Output stores are already completed before COMPUTE responds.
      when(computedReg) {
        resultReg := RET_SUCCESS
      }.otherwise {
        resultReg := RET_ERROR
      }
      state := sRespond
    }

    is(sRespond) {
      when(xdReg) {
        io.resp.valid := true.B
        when(io.resp.fire) {
          state := sIdle
        }
      }.otherwise {
        state := sIdle
      }
    }
  }
}
