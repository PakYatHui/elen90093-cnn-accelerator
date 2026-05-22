package myaccelerators

import chisel3._
import chisel3.util._

import org.chipsalliance.cde.config._
import org.chipsalliance.diplomacy.lazymodule._
import freechips.rocketchip.tile._
import freechips.rocketchip.rocket._

// =============================================================================
// Phase 2 fixed16 shared 2D tile buffer accelerator.
// Interface remains CONFIG -> KERNEL -> DATA -> COMPUTE -> STORE.
//
// This version deliberately mirrors the working feature/accelerator-fsm store path:
//   - COMPUTE only fills an internal outputBuf.
//   - STORE performs the 64-bit packed M_XWR writes in a separate sStore state.
//   - The 2D tileBuf is retained for shared input reuse.
//   - tileWidth uses +& so 5x5 gets width 8, not truncated to 0.
// =============================================================================

class MyConvAccel(opcodes: OpcodeSet)(implicit p: Parameters) extends LazyRoCC(opcodes) {
  println("DEBUG: Elaborating MyConvAccel Phase 2 2D tileBuf with FSM-branch store path version")
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

  val TILE_OUTPUTS     = 4
  val MAX_TILE_WIDTH   = MAX_KERNEL_SIZE + TILE_OUTPUTS - 1 // 8 for 5x5.
  val MAX_TILE_ELEMS   = MAX_KERNEL_SIZE * MAX_TILE_WIDTH   // 40 for 5x5.

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

  // Main FSM states
  val sIdle :: sDecode :: sConfig :: sKernel :: sLoad :: sCompute :: sStore :: sRespond :: Nil = Enum(8)
  val state = RegInit(sIdle)

  // Fixed16 shared tile engine states
  val ftIdle :: ftLoadTile :: ftLoadWait :: ftCompute :: ftStore :: ftStoreWait :: ftDone :: Nil = Enum(7)
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
  // Kernel, bias, and shared tile storage
  // ---------------------------------------------------------------------------
  val kernelBuf = Reg(Vec(MAX_KERNEL_ELEMS, UInt(16.W)))
  val outputBuf = Reg(Vec(INPUT_ELEMS, UInt(16.W)))
  val biasBuf   = RegInit(0.U(16.W))

  // 2D shared tile buffer:
  //   rows = kernelSize
  //   cols = kernelSize + TILE_OUTPUTS - 1
  //
  // For 5x5 and four output lanes, the tile is 5 x 8 = 40 input values.
  // Output lane o uses tileBuf(kr)(kc + o).
  val tileBuf = Reg(Vec(MAX_KERNEL_SIZE, Vec(MAX_TILE_WIDTH, UInt(16.W))))

  // ---------------------------------------------------------------------------
  // Kernel/bias preload bookkeeping
  // ---------------------------------------------------------------------------
  val preloadIdx      = RegInit(0.U(6.W))
  val preloadInflight = RegInit(false.B)
  val preloadType     = RegInit(PRELOAD_KERNEL)
  val biasLoadedReg   = RegInit(true.B)

  // ---------------------------------------------------------------------------
  // Shared tile engine bookkeeping
  // ---------------------------------------------------------------------------
  val tileBaseIdx = RegInit(0.U(11.W))

  // Loader cursor. 5x5 needs 40 elements, so tileLoadCnt must be 6 bits.
  val tileLoadRow = RegInit(0.U(3.W))
  val tileLoadCol = RegInit(0.U(4.W))
  val tileLoadCnt = RegInit(0.U(6.W))

  // Compute cursor. One kernel row is consumed per compute cycle.
  val tileMacRow = RegInit(0.U(3.W))

  val tileAcc    = RegInit(VecInit(Seq.fill(TILE_OUTPUTS)(0.S(40.W))))
  val tileOutReg = RegInit(VecInit(Seq.fill(TILE_OUTPUTS)(0.U(16.W))))

  // Store bookkeeping. This intentionally follows the working FSM branch:
  // compute fills outputBuf first, then STORE writes outputBuf to memory.
  val storeIdx      = RegInit(0.U(11.W))
  val storeInflight = RegInit(false.B)

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

  // This Phase 2 debug version is fixed16-only.
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
  // Tile geometry and loader address generation
  // ---------------------------------------------------------------------------
  val tileRadius  = kernelSizeReg >> 1
  val tileBaseRow = tileBaseIdx(9, 5)
  val tileBaseCol = tileBaseIdx(4, 0)

  // tileWidth = kernelSize + 3.
  // Use +& so 5 + 3 becomes 8, not 3-bit truncated zero.
  val tileWidth = kernelSizeReg +& (TILE_OUTPUTS - 1).U(3.W)

  // Maximum runtime tileElems is 5 * 8 = 40, which fits in 6 bits.
  val tileElems = (kernelSizeReg * tileWidth)(5, 0)

  val tileLoadInRowS =
    tileBaseRow.zext + tileLoadRow.zext - tileRadius.zext

  val tileLoadInColS =
    tileBaseCol.zext + tileLoadCol.zext - tileRadius.zext

  val tileLoadInputValid =
    tileLoadInRowS >= 0.S && tileLoadInRowS < INPUT_SIZE.S &&
    tileLoadInColS >= 0.S && tileLoadInColS < INPUT_SIZE.S

  val tileLoadMemIndex = (tileLoadInRowS.asUInt << 5) + tileLoadInColS.asUInt
  val tileLoadMemAddr  = inputAddrReg + (tileLoadMemIndex << 1)

  // ---------------------------------------------------------------------------
  // Compute datapath
  // ---------------------------------------------------------------------------
  val biasTerm = Wire(SInt(40.W))
  biasTerm := Mux(biasEnableReg, biasBuf.asSInt, 0.S(40.W))

  val tileTerms   = Wire(Vec(TILE_OUTPUTS, Vec(MAX_KERNEL_SIZE, SInt(40.W))))
  val tileRowSum  = Wire(Vec(TILE_OUTPUTS, SInt(40.W)))
  val tileNextAcc = Wire(Vec(TILE_OUTPUTS, SInt(40.W)))

  for (o <- 0 until TILE_OUTPUTS) {
    for (kc <- 0 until MAX_KERNEL_SIZE) {
      val validKc = kc.U < kernelSizeReg

      // For output lane o, the input column is kc + o.
      // The largest value is 4 + 3 = 7, so 3 bits are enough for the final Vec index.
      val tileCol = (kc + o).U(4.W)

      val kernelElem = ((tileMacRow * kernelSizeReg) +& kc.U(3.W))(4, 0)

      val inVal  = tileBuf(tileMacRow)(tileCol(2, 0)).asSInt
      val kerVal = kernelBuf(kernelElem).asSInt

      val rawProduct = inVal * kerVal
      val scaledTerm = (rawProduct >> 8).asSInt

      tileTerms(o)(kc) := Mux(validKc, scaledTerm, 0.S(40.W))
    }

    val sum01 = (tileTerms(o)(0) + tileTerms(o)(1)).asUInt(39, 0).asSInt
    val sum23 = (tileTerms(o)(2) + tileTerms(o)(3)).asUInt(39, 0).asSInt

    tileRowSum(o)  := (sum01 + sum23 + tileTerms(o)(4)).asUInt(39, 0).asSInt
    tileNextAcc(o) := (tileAcc(o) + tileRowSum(o)).asUInt(39, 0).asSInt
  }

  val tileStoreData = Cat(tileOutReg(3), tileOutReg(2), tileOutReg(1), tileOutReg(0))

  // ---------------------------------------------------------------------------
  // Helper methods
  // ---------------------------------------------------------------------------
  def resetTileLoadCursors(): Unit = {
    tileLoadRow := 0.U
    tileLoadCol := 0.U
    tileLoadCnt := 0.U
  }

  def resetTileComputeCursors(): Unit = {
    tileMacRow := 0.U
  }

  def resetTileAccumulators(): Unit = {
    for (i <- 0 until TILE_OUTPUTS) {
      tileAcc(i) := 0.S
      tileOutReg(i) := 0.U
    }
  }

  def advanceTileLoadCursor(): Unit = {
    tileLoadCnt := tileLoadCnt + 1.U

    val nextCol = tileLoadCol + 1.U
    when(nextCol >= tileWidth) {
      tileLoadCol := 0.U
      tileLoadRow := tileLoadRow + 1.U
    }.otherwise {
      tileLoadCol := nextCol
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
          biasLoadedReg := !biasEnableReg

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
          resetTileLoadCursors()
          resetTileComputeCursors()
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
          storeIdx := 0.U
          storeInflight := false.B
          state := sStore
        }.otherwise {
          resultReg := RET_ERROR
          state := sRespond
        }

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
        biasLoadedReg := true.B
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

        }.elsewhen(biasEnableReg && !biasLoadedReg) {
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
          biasLoadedReg := true.B
        }

        preloadInflight := false.B
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
            resetTileLoadCursors()
            resetTileComputeCursors()
            resetTileAccumulators()
            fixedTileState := ftLoadTile
          }
        }

        is(ftLoadTile) {
          when(tileLoadCnt >= tileElems) {
            resetTileComputeCursors()
            fixedTileState := ftCompute

          }.elsewhen(!tileLoadInputValid) {
            tileBuf(tileLoadRow)(tileLoadCol(2, 0)) := 0.U
            advanceTileLoadCursor()

          }.otherwise {
            io.mem.req.valid := true.B
            io.mem.req.bits.addr := tileLoadMemAddr
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
            tileBuf(tileLoadRow)(tileLoadCol(2, 0)) := io.mem.resp.bits.data(15, 0)
            advanceTileLoadCursor()
            fixedTileState := ftLoadTile
          }
        }

        is(ftCompute) {
          when(tileMacRow === (kernelSizeReg - 1.U)) {
            for (i <- 0 until TILE_OUTPUTS) {
              val finalOut = (tileNextAcc(i) + biasTerm).asUInt(15, 0)
              tileOutReg(i) := finalOut
              outputBuf((tileBaseIdx + i.U)(9, 0)) := finalOut
            }

            // Do not write memory here. The working FSM branch writes output
            // memory only from sStore after COMPUTE has completed.
            tileBaseIdx := tileBaseIdx + TILE_OUTPUTS.U
            fixedTileState := ftIdle

          }.otherwise {
            for (i <- 0 until TILE_OUTPUTS) {
              tileAcc(i) := tileNextAcc(i)
            }

            tileMacRow := tileMacRow + 1.U
          }
        }

        is(ftStore) {
          // Unused in this FSM-branch-store version.
          fixedTileState := ftIdle
        }

        is(ftStoreWait) {
          // Unused in this FSM-branch-store version.
          fixedTileState := ftIdle
        }

        is(ftDone) {
          computedReg := true.B
          resultReg := RET_SUCCESS
          state := sRespond
        }
      }
    }

    is(sStore) {
      // Store path mirrors the working feature/accelerator-fsm branch:
      // one aligned 64-bit M_XWR writes four packed 16-bit output elements.
      when(storeIdx < INPUT_ELEMS.U) {
        when(!storeInflight) {
          io.mem.req.valid := true.B
          io.mem.req.bits.addr := outputAddrReg + (storeIdx << 1)
          io.mem.req.bits.tag := 2.U
          io.mem.req.bits.cmd := M_XWR
          io.mem.req.bits.size := 3.U
          io.mem.req.bits.signed := false.B
          io.mem.req.bits.data := Cat(
            outputBuf((storeIdx + 3.U)(9, 0)),
            outputBuf((storeIdx + 2.U)(9, 0)),
            outputBuf((storeIdx + 1.U)(9, 0)),
            outputBuf(storeIdx(9, 0))
          )
          io.mem.req.bits.dprv := dprvReg

          when(io.mem.req.fire) {
            storeInflight := true.B
          }
        }

        when(storeInflight && io.mem.resp.valid) {
          storeInflight := false.B
          storeIdx := storeIdx + TILE_OUTPUTS.U
        }

      }.otherwise {
        resultReg := RET_SUCCESS
        state := sRespond
      }
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
