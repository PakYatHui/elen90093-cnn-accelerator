package myaccelerators

import chisel3._
import chisel3.util._

import org.chipsalliance.cde.config._
import org.chipsalliance.diplomacy.lazymodule._
import freechips.rocketchip.tile._
import freechips.rocketchip.rocket._

class MyConvAccel(opcodes: OpcodeSet)(implicit p: Parameters) extends LazyRoCC(opcodes) {
  println("DEBUG: Elaborating MyConvAccel")
  override lazy val module = new MyConvAccelModule(this)
}

class MyConvAccelModule(outer: MyConvAccel)(implicit p: Parameters)
  extends LazyRoCCModuleImp(outer)
  with HasCoreParameters {

  // Buffer incoming commands from the RoCC interface.
  val cmd = Queue(io.cmd, 1)

  // Fixed design parameters.
  val INPUT_SIZE       = 32
  val INPUT_ELEMS      = INPUT_SIZE * INPUT_SIZE
  val MAX_KERNEL_SIZE  = 5
  val MAX_KERNEL_ELEMS = MAX_KERNEL_SIZE * MAX_KERNEL_SIZE

  // Return values.
  val RET_ERROR   = 0.U(xLen.W)
  val RET_SUCCESS = 1.U(xLen.W)

  // funct7 encoding.
  val FUNCT_CONFIG  = 0.U(7.W)
  val FUNCT_LOAD    = 1.U(7.W)
  val FUNCT_COMPUTE = 2.U(7.W)
  val FUNCT_STORE   = 3.U(7.W)

  // FSM states.
  val sIdle :: sDecode :: sConfig :: sLoad :: sCompute :: sStore :: sRespond :: Nil = Enum(7)
  val state = RegInit(sIdle)

  // Latched command fields.
  val functReg = RegInit(0.U(7.W))
  val rs1Reg   = RegInit(0.U(xLen.W))
  val rs2Reg   = RegInit(0.U(xLen.W))
  val rdReg    = RegInit(0.U(5.W))
  val dprvReg  = RegInit(0.U(2.W))

  // Runtime configuration registers.
  val inputAddrReg   = RegInit(0.U(xLen.W))
  val kernelAddrReg  = RegInit(0.U(xLen.W))
  val outputAddrReg  = RegInit(0.U(xLen.W))
  val kernelSizeReg  = RegInit(0.U(3.W))
  val kernelElemsReg = RegInit(0.U(6.W))

  // Command-sequence protection flags.
  val configuredReg = RegInit(false.B)
  val loadedReg     = RegInit(false.B)
  val computedReg   = RegInit(false.B)

  // Response register. The design only exposes success or error.
  val resultReg = RegInit(RET_ERROR)

  // Internal accelerator buffers.
  val inputBuf  = Reg(Vec(INPUT_ELEMS, SInt(16.W)))
  val kernelBuf = Reg(Vec(MAX_KERNEL_ELEMS, SInt(16.W)))
  val outputBuf = Reg(Vec(INPUT_ELEMS, SInt(16.W)))

  // Load/store bookkeeping. These counters must hold the terminal value 1024.
  val inputLoadIdx  = RegInit(0.U(11.W))
  val kernelLoadIdx = RegInit(0.U(6.W))
  val storeIdx      = RegInit(0.U(11.W))
  val memInflight   = RegInit(false.B)
  val issuedIsInput = RegInit(false.B)
  val issuedIndex   = RegInit(0.U(11.W))

  // Compute bookkeeping.
  val outRow = RegInit(0.U(6.W))
  val outCol = RegInit(0.U(6.W))

  // Helper wires.
  val doConfig  = functReg === FUNCT_CONFIG
  val doLoad    = functReg === FUNCT_LOAD
  val doCompute = functReg === FUNCT_COMPUTE
  val doStore   = functReg === FUNCT_STORE

  val validKernelSize =
    (rs1Reg === 1.U) || (rs1Reg === 3.U) || (rs1Reg === 5.U)

  // Default command handshake.
  cmd.ready := false.B

  // Default response interface.
  io.resp.valid := false.B
  io.resp.bits.rd := rdReg
  io.resp.bits.data := resultReg

  io.busy := state =/= sIdle
  io.interrupt := false.B

  // Default memory request interface.
  io.mem.req.valid := false.B
  io.mem.req.bits.addr := 0.U
  io.mem.req.bits.tag := 0.U
  io.mem.req.bits.cmd := M_XRD
  io.mem.req.bits.size := 1.U
  io.mem.req.bits.phys := false.B
  io.mem.req.bits.signed := true.B
  io.mem.req.bits.data := 0.U
  io.mem.req.bits.dprv := dprvReg

  when(cmd.fire) {
    printf("[MyConvAccel] CMD fire funct=%d rs1=%x rs2=%x rd=%d state=%d\n",
      cmd.bits.inst.funct, cmd.bits.rs1, cmd.bits.rs2, cmd.bits.inst.rd, state)
  }

  // Return the input element selected by a variable-size centred kernel window.
  // Out-of-range accesses implement zero padding.
  def getInputAt(baseRow: UInt, baseCol: UInt, kr: Int, kc: Int): SInt = {
    val radius = kernelSizeReg >> 1

    val rowS = baseRow.zext + kr.S(4.W) - radius.zext
    val colS = baseCol.zext + kc.S(4.W) - radius.zext

    val rowValid = rowS >= 0.S && rowS < INPUT_SIZE.S
    val colValid = colS >= 0.S && colS < INPUT_SIZE.S

    val rowIdx = rowS.asUInt
    val colIdx = colS.asUInt
    val inputIndex = (rowIdx << 5) + colIdx

<<<<<<< Updated upstream
    Mux(rowValid && colValid, inputBuf(inputIndex), 0.S(16.W))
=======
    Mux(rowValid && colValid, inputBuf(inputIndex(9, 0)), 0.S(16.W))
>>>>>>> Stashed changes
  }

  // Parallel MAC tree for one output element. The hardware contains the maximum
  // 5x5 datapath, while runtime masks select 1x1, 3x3, or 5x5 operation.
  val macTerms = Wire(Vec(MAX_KERNEL_ELEMS, SInt(32.W)))

  for (kr <- 0 until MAX_KERNEL_SIZE) {
    for (kc <- 0 until MAX_KERNEL_SIZE) {
      val termIdx = kr * MAX_KERNEL_SIZE + kc
      val active = kr.U < kernelSizeReg && kc.U < kernelSizeReg
      val kernelIndex = kr.U(3.W) * kernelSizeReg + kc.U(3.W)
<<<<<<< Updated upstream
      val inVal = getInputAt(outRow, outCol, kr, kc)
      val kerVal = kernelBuf(kernelIndex)
=======

      //用到上面函数，拿到原矩阵对应位置数值（含zero padding）
      val inVal = getInputAt(outRow, outCol, kr, kc)//input阵时二维存储的
      val kerVal = kernelBuf(kernelIndex(4, 0))//kernel是一维存储的
>>>>>>> Stashed changes

      // 8.8 x 8.8 produces 16.16. Shift right by 8 to return to 8.8 scale.
      macTerms(termIdx) := Mux(active, (inVal * kerVal) >> 8, 0.S(32.W))
    }
  }

  val macSum = macTerms.reduce(_ + _)

  // Keep the original truncation behaviour and store the lower 16 bits.
  val macOut16 = (macSum.asUInt)(15, 0).asSInt
  val outIndex = (outRow << 5) + outCol

  switch(state) {

    is(sIdle) {
      cmd.ready := true.B

      when(cmd.fire) {
        functReg := cmd.bits.inst.funct
        rs1Reg   := cmd.bits.rs1
        rs2Reg   := cmd.bits.rs2
        rdReg    := cmd.bits.inst.rd
        dprvReg  := cmd.bits.status.dprv
        state    := sDecode
      }
    }

    is(sDecode) {
      when(doConfig) {
        state := sConfig

      }.elsewhen(doLoad) {
        when(configuredReg) {
          inputAddrReg  := rs1Reg
          kernelAddrReg := rs2Reg
          inputLoadIdx  := 0.U
          kernelLoadIdx := 0.U
          memInflight   := false.B
          printf("[MyConvAccel] Decode -> Load inputAddr=%x kernelAddr=%x kernelSize=%d\n",
            rs1Reg, rs2Reg, kernelSizeReg)
          state := sLoad
        }.otherwise {
          resultReg := RET_ERROR
          printf("[MyConvAccel] LOAD rejected: accelerator is not configured\n")
          state := sRespond
        }

      }.elsewhen(doCompute) {
        when(configuredReg && loadedReg) {
          outRow := 0.U
          outCol := 0.U
          printf("[MyConvAccel] Decode -> Compute kernelSize=%d\n", kernelSizeReg)
          state := sCompute
        }.otherwise {
          resultReg := RET_ERROR
          printf("[MyConvAccel] COMPUTE rejected: missing CONFIG or LOAD\n")
          state := sRespond
        }

      }.elsewhen(doStore) {
        when(configuredReg && loadedReg && computedReg) {
          storeIdx := 0.U
          printf("[MyConvAccel] Decode -> Store outputAddr=%x\n", outputAddrReg)
          state := sStore
        }.otherwise {
          resultReg := RET_ERROR
          printf("[MyConvAccel] STORE rejected: missing CONFIG, LOAD, or COMPUTE\n")
          state := sRespond
        }

      }.otherwise {
        resultReg := RET_ERROR
        printf("[MyConvAccel] Illegal funct=%d\n", functReg)
        state := sRespond
      }
    }

    is(sConfig) {
      when(validKernelSize) {
        kernelSizeReg := rs1Reg(2, 0)
        outputAddrReg := rs2Reg

        when(rs1Reg === 1.U) {
          kernelElemsReg := 1.U
        }.elsewhen(rs1Reg === 3.U) {
          kernelElemsReg := 9.U
        }.otherwise {
          kernelElemsReg := 25.U
        }

        configuredReg := true.B
        loadedReg := false.B
        computedReg := false.B
        resultReg := RET_SUCCESS

        printf("[MyConvAccel] CONFIG success kernelSize=%d outputAddr=%x\n", rs1Reg, rs2Reg)
      }.otherwise {
        configuredReg := false.B
        loadedReg := false.B
        computedReg := false.B
        kernelSizeReg := 0.U
        kernelElemsReg := 0.U
        resultReg := RET_ERROR

        printf("[MyConvAccel] CONFIG error invalid kernelSize=%d\n", rs1Reg)
      }

      state := sRespond
    }

    is(sLoad) {
      // Issue one read at a time: first the 32x32 input, then the active kernel.
      when(!memInflight) {
        when(inputLoadIdx < INPUT_ELEMS.U) {
          io.mem.req.valid := true.B
          io.mem.req.bits.addr := inputAddrReg + (inputLoadIdx << 1)
          io.mem.req.bits.tag := 0.U
          io.mem.req.bits.cmd := M_XRD
          io.mem.req.bits.size := 1.U
          io.mem.req.bits.signed := true.B

          when(io.mem.req.fire) {
            memInflight := true.B
            issuedIsInput := true.B
            issuedIndex := inputLoadIdx
          }

        }.elsewhen(kernelLoadIdx < kernelElemsReg) {
          io.mem.req.valid := true.B
          io.mem.req.bits.addr := kernelAddrReg + (kernelLoadIdx << 1)
          io.mem.req.bits.tag := 1.U
          io.mem.req.bits.cmd := M_XRD
          io.mem.req.bits.size := 1.U
          io.mem.req.bits.signed := true.B

          when(io.mem.req.fire) {
            memInflight := true.B
            issuedIsInput := false.B
            issuedIndex := kernelLoadIdx
          }

        }.otherwise {
          loadedReg := true.B
          computedReg := false.B
          resultReg := RET_SUCCESS
          printf("[MyConvAccel] LOAD complete\n")
          state := sRespond
        }
      }

      when(io.mem.resp.valid) {
        val loadedData = io.mem.resp.bits.data(15, 0).asSInt

        when(issuedIsInput) {
          inputBuf(issuedIndex(9, 0)) := loadedData
          inputLoadIdx := inputLoadIdx + 1.U
        }.otherwise {
          kernelBuf(issuedIndex(4, 0)) := loadedData
          kernelLoadIdx := kernelLoadIdx + 1.U
        }

        memInflight := false.B
      }
    }

    is(sCompute) {
      // Compute one output element per cycle.
      outputBuf(outIndex(9, 0)) := macOut16

      when(outCol === (INPUT_SIZE - 1).U) {
        outCol := 0.U
        when(outRow === (INPUT_SIZE - 1).U) {
          outRow := 0.U
          computedReg := true.B
          resultReg := RET_SUCCESS
          printf("[MyConvAccel] COMPUTE complete\n")
          state := sRespond
        }.otherwise {
          outRow := outRow + 1.U
        }
      }.otherwise {
        outCol := outCol + 1.U
      }
    }

<<<<<<< Updated upstream
    is(sStore) {
      // Write the 32x32 output matrix back to memory.
      when(storeIdx < INPUT_ELEMS.U) {
        io.mem.req.valid := true.B
        io.mem.req.bits.addr := outputAddrReg + (storeIdx << 1)
        io.mem.req.bits.tag := 2.U
        io.mem.req.bits.cmd := M_XWR
        io.mem.req.bits.size := 1.U
        io.mem.req.bits.signed := true.B
        io.mem.req.bits.data := outputBuf(storeIdx).pad(xLen).asUInt
=======


is(sStore) {
  // Write the 32x32 output matrix back to memory.
  // Use one 64-bit full store for every four 16-bit output elements.
  // This avoids TileLink PutPartial transactions.

  when(storeIdx < INPUT_ELEMS.U) {
    io.mem.req.valid := true.B
>>>>>>> Stashed changes

    // storeIdx is the starting int16 index of this 64-bit store.
    // Byte offset = storeIdx * 2.
    io.mem.req.bits.addr := outputAddrReg + (storeIdx << 1)

    io.mem.req.bits.tag := 2.U
    io.mem.req.bits.cmd := M_XWR

    // size = log2(bytes). 3 means 8 bytes = 64-bit full store.
    io.mem.req.bits.size := 3.U

    io.mem.req.bits.signed := false.B

    // Pack four int16 outputs into one 64-bit word.
    // Little-endian layout:
    // bits [15:0]   -> outputBuf[storeIdx]
    // bits [31:16]  -> outputBuf[storeIdx + 1]
    // bits [47:32]  -> outputBuf[storeIdx + 2]
    // bits [63:48]  -> outputBuf[storeIdx + 3]
    io.mem.req.bits.data := Cat(
      outputBuf((storeIdx + 3.U)(9, 0)).asUInt,
      outputBuf((storeIdx + 2.U)(9, 0)).asUInt,
      outputBuf((storeIdx + 1.U)(9, 0)).asUInt,
      outputBuf(storeIdx(9, 0)).asUInt
    )

    when(io.mem.req.fire) {
      // Four int16 elements have been stored.
      storeIdx := storeIdx + 4.U
    }
  }.otherwise {
    resultReg := RET_SUCCESS
    printf("[MyConvAccel] STORE complete\n")
    state := sRespond
  }
}

    is(sRespond) {
      io.resp.valid := true.B

      when(io.resp.fire) {
        printf("[MyConvAccel] RESP fire rd=%d data=%x\n", rdReg, resultReg)
        state := sIdle
      }
    }
  }
}
