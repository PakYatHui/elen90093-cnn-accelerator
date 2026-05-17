package myaccelerators

import chisel3._
import chisel3.util._

import org.chipsalliance.cde.config._
import org.chipsalliance.diplomacy.lazymodule._
import freechips.rocketchip.tile._
import freechips.rocketchip.rocket._
import hardfloat._

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

  // Data type encoding.
  val DATA_FIXED16 = 0.U(2.W)
  val DATA_FLOAT16 = 1.U(2.W)

  // Float16 parameters for HardFloat.
  val FP16_EXP_WIDTH = 5
  val FP16_SIG_WIDTH = 11

  // funct7 encoding.
  val FUNCT_CONFIG  = 0.U(7.W)
  val FUNCT_DATA    = 1.U(7.W)
  val FUNCT_COMPUTE = 2.U(7.W)
  val FUNCT_STORE   = 3.U(7.W)
  val FUNCT_KERNEL  = 4.U(7.W)

  // FSM states.
  // State count is unchanged.
  val sIdle :: sDecode :: sConfig :: sKernel :: sLoad :: sCompute :: sStore :: sRespond :: Nil = Enum(8)
  val state = RegInit(sIdle)

  // Latched command fields.
  val functReg = RegInit(0.U(7.W))
  val rs1Reg   = RegInit(0.U(xLen.W))
  val rs2Reg   = RegInit(0.U(xLen.W))
  val rdReg    = RegInit(0.U(5.W))
  val dprvReg  = RegInit(0.U(2.W))
  val xdReg    = RegInit(false.B)

  // Runtime configuration registers.
  val inputAddrReg   = RegInit(0.U(xLen.W))
  val kernelAddrReg  = RegInit(0.U(xLen.W))
  val outputAddrReg  = RegInit(0.U(xLen.W))
  val biasAddrReg    = RegInit(0.U(xLen.W))
  val kernelSizeReg  = RegInit(0.U(3.W))
  val kernelElemsReg = RegInit(0.U(6.W))
  val dataTypeReg    = RegInit(DATA_FIXED16)
  val biasEnableReg  = RegInit(false.B)

  // Command-sequence protection flags.
  val configuredReg       = RegInit(false.B)
  val kernelConfiguredReg = RegInit(false.B)
  val loadedReg           = RegInit(false.B)
  val computedReg         = RegInit(false.B)

  // Response register.
  val resultReg = RegInit(RET_ERROR)

  // Internal accelerator buffers.
  // UInt(16.W) allows both fixed16 raw bits and float16 raw bits.
  val inputBuf  = Reg(Vec(INPUT_ELEMS, UInt(16.W)))
  val kernelBuf = Reg(Vec(MAX_KERNEL_ELEMS, UInt(16.W)))
  val outputBuf = Reg(Vec(INPUT_ELEMS, UInt(16.W)))

  // Load/store bookkeeping.
  val inputLoadIdx  = RegInit(0.U(11.W))
  val kernelLoadIdx = RegInit(0.U(6.W))
  val storeIdx      = RegInit(0.U(11.W))
  val memInflight   = RegInit(false.B)
  val issuedIsInput = RegInit(false.B)
  val issuedIndex   = RegInit(0.U(11.W))

  // Compute output pixel bookkeeping.
  val outRow = RegInit(0.U(6.W))
  val outCol = RegInit(0.U(6.W))

  // Sequential MAC bookkeeping.
  val macKr  = RegInit(0.U(3.W))
  val macKc  = RegInit(0.U(3.W))
  val macIdx = RegInit(0.U(6.W))

  // Sequential accumulators.
  val fixedAcc = RegInit(0.S(40.W))
  val floatAcc = RegInit(0.U(16.W))

  // Helper wires.
  val doConfig  = functReg === FUNCT_CONFIG
  val doData    = functReg === FUNCT_DATA
  val doCompute = functReg === FUNCT_COMPUTE
  val doStore   = functReg === FUNCT_STORE
  val doKernel  = functReg === FUNCT_KERNEL

  val validKernelSize =
    (rs1Reg === 1.U) || (rs1Reg === 3.U) || (rs1Reg === 5.U)

  val validDataType =
    (rs2Reg(1, 0) === DATA_FIXED16) || (rs2Reg(1, 0) === DATA_FLOAT16)

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
    printf("[MyConvAccel] CMD fire funct=%d rs1=%x rs2=%x rd=%d xd=%d state=%d\n",
      cmd.bits.inst.funct,
      cmd.bits.rs1,
      cmd.bits.rs2,
      cmd.bits.inst.rd,
      cmd.bits.inst.xd,
      state
    )
  }

  // Float16 multiply using HardFloat.
  def fp16Mul(a: UInt, b: UInt): UInt = {
    val mul = Module(new MulRecFN(FP16_EXP_WIDTH, FP16_SIG_WIDTH))

    mul.io.a := recFNFromFN(FP16_EXP_WIDTH, FP16_SIG_WIDTH, a)
    mul.io.b := recFNFromFN(FP16_EXP_WIDTH, FP16_SIG_WIDTH, b)
    mul.io.roundingMode := 0.U(3.W)
    mul.io.detectTininess := 0.U(1.W)

    fNFromRecFN(FP16_EXP_WIDTH, FP16_SIG_WIDTH, mul.io.out)
  }

  // Float16 add using HardFloat.
  def fp16Add(a: UInt, b: UInt): UInt = {
    val add = Module(new AddRecFN(FP16_EXP_WIDTH, FP16_SIG_WIDTH))

    add.io.subOp := false.B
    add.io.a := recFNFromFN(FP16_EXP_WIDTH, FP16_SIG_WIDTH, a)
    add.io.b := recFNFromFN(FP16_EXP_WIDTH, FP16_SIG_WIDTH, b)
    add.io.roundingMode := 0.U(3.W)
    add.io.detectTininess := 0.U(1.W)

    fNFromRecFN(FP16_EXP_WIDTH, FP16_SIG_WIDTH, add.io.out)
  }

  // Return fixed16 input value for the selected runtime kernel position.
  // Out-of-range accesses implement zero padding.
  def getInputAtFixed(baseRow: UInt, baseCol: UInt, kr: UInt, kc: UInt): SInt = {
    val radius = kernelSizeReg >> 1

    val rowS = baseRow.zext + kr.zext - radius.zext
    val colS = baseCol.zext + kc.zext - radius.zext

    val rowValid = rowS >= 0.S && rowS < INPUT_SIZE.S
    val colValid = colS >= 0.S && colS < INPUT_SIZE.S

    val rowIdx = rowS.asUInt
    val colIdx = colS.asUInt
    val inputIndex = (rowIdx << 5) + colIdx

    Mux(rowValid && colValid, inputBuf(inputIndex(9, 0)).asSInt, 0.S(16.W))
  }

  // Return raw float16 bits for the selected runtime kernel position.
  // Out-of-range accesses return +0.0 half-precision.
  def getInputAtFloat16(baseRow: UInt, baseCol: UInt, kr: UInt, kc: UInt): UInt = {
    val radius = kernelSizeReg >> 1

    val rowS = baseRow.zext + kr.zext - radius.zext
    val colS = baseCol.zext + kc.zext - radius.zext

    val rowValid = rowS >= 0.S && rowS < INPUT_SIZE.S
    val colValid = colS >= 0.S && colS < INPUT_SIZE.S

    val rowIdx = rowS.asUInt
    val colIdx = colS.asUInt
    val inputIndex = (rowIdx << 5) + colIdx

    Mux(rowValid && colValid, inputBuf(inputIndex(9, 0)), 0.U(16.W))
  }

  // Current output index.
  val outIndex = (outRow << 5) + outCol

  // Sequential fixed16 MAC datapath.
  val fixedInVal  = getInputAtFixed(outRow, outCol, macKr, macKc)
  val fixedKerVal = kernelBuf(macIdx(4, 0)).asSInt

  // 8.8 x 8.8 produces 16.16.
  // Shift right by 8 to return to 8.8 scale.
  val fixedTermRaw = (fixedInVal * fixedKerVal) >> 8
  val fixedTerm = Wire(SInt(40.W))
  fixedTerm := fixedTermRaw

  val nextFixedAcc = (fixedAcc + fixedTerm).asUInt(39, 0).asSInt

  // Sequential Float16 MAC datapath.
  val floatInVal   = getInputAtFloat16(outRow, outCol, macKr, macKc)
  val floatKerVal  = kernelBuf(macIdx(4, 0))
  val floatTerm    = fp16Mul(floatInVal, floatKerVal)
  val nextFloatAcc = fp16Add(floatAcc, floatTerm)

  // Kernel and output scanning helpers.
  val lastMacTerm   = macIdx === (kernelElemsReg - 1.U)
  val lastKernelCol = macKc === (kernelSizeReg - 1.U)
  val lastOutPixel  = (outRow === (INPUT_SIZE - 1).U) && (outCol === (INPUT_SIZE - 1).U)

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
          printf("[MyConvAccel] KERNEL rejected: accelerator is not configured\n")
          state := sRespond
        }

      }.elsewhen(doData) {
        when(configuredReg && kernelConfiguredReg) {
          when(biasEnableReg) {
            resultReg := RET_ERROR
            printf("[MyConvAccel] DATA rejected: bias datapath is not implemented yet\n")
            state := sRespond

          }.otherwise {
            inputAddrReg  := rs1Reg
            outputAddrReg := rs2Reg
            inputLoadIdx  := 0.U
            kernelLoadIdx := 0.U
            memInflight   := false.B

            printf("[MyConvAccel] Decode -> Data inputAddr=%x outputAddr=%x kernelAddr=%x kernelSize=%d dataType=%d biasEnable=%d\n",
              rs1Reg, rs2Reg, kernelAddrReg, kernelSizeReg, dataTypeReg, biasEnableReg)

            state := sLoad
          }

        }.otherwise {
          resultReg := RET_ERROR
          printf("[MyConvAccel] DATA rejected: missing CONFIG or KERNEL\n")
          state := sRespond
        }

      }.elsewhen(doCompute) {
        when(configuredReg && kernelConfiguredReg && loadedReg) {
          outRow := 0.U
          outCol := 0.U

          macKr  := 0.U
          macKc  := 0.U
          macIdx := 0.U

          fixedAcc := 0.S
          floatAcc := 0.U

          printf("[MyConvAccel] Decode -> Compute kernelSize=%d dataType=%d biasEnable=%d\n",
            kernelSizeReg, dataTypeReg, biasEnableReg)

          state := sCompute
        }.otherwise {
          resultReg := RET_ERROR
          printf("[MyConvAccel] COMPUTE rejected: missing CONFIG, KERNEL, or DATA\n")
          state := sRespond
        }

      }.elsewhen(doStore) {
        when(configuredReg && kernelConfiguredReg && loadedReg && computedReg) {
          storeIdx := 0.U
          memInflight := false.B

          printf("[MyConvAccel] Decode -> Store outputAddr=%x dataType=%d\n",
            outputAddrReg, dataTypeReg)

          state := sStore
        }.otherwise {
          resultReg := RET_ERROR
          printf("[MyConvAccel] STORE rejected: missing CONFIG, KERNEL, DATA, or COMPUTE\n")
          state := sRespond
        }

      }.otherwise {
        resultReg := RET_ERROR
        printf("[MyConvAccel] Illegal funct=%d\n", functReg)
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

        printf("[MyConvAccel] CONFIG success kernelSize=%d dataType=%d biasEnable=%d\n",
          rs1Reg, rs2Reg(1, 0), rs2Reg(2))

      }.otherwise {
        configuredReg := false.B
        kernelConfiguredReg := false.B
        loadedReg := false.B
        computedReg := false.B
        kernelSizeReg := 0.U
        kernelElemsReg := 0.U
        dataTypeReg := DATA_FIXED16
        biasEnableReg := false.B
        resultReg := RET_ERROR

        printf("[MyConvAccel] CONFIG error kernelSize=%d dataType=%d\n",
          rs1Reg, rs2Reg(1, 0))
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

      printf("[MyConvAccel] KERNEL success kernelAddr=%x biasAddr=%x biasEnable=%d\n",
        rs1Reg, rs2Reg, biasEnableReg)

      state := sRespond
    }

    is(sLoad) {
      // Issue one read at a time: first the 32x32 input, then the active kernel.
      // Both fixed16 and float16 use 16-bit elements.
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

          printf("[MyConvAccel] DATA/LOAD complete\n")

          state := sRespond
        }
      }

      when(io.mem.resp.valid) {
        val loadedData = io.mem.resp.bits.data(15, 0)

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
      // Sequential MAC:
      // one kernel term is accumulated per cycle.

      when(lastMacTerm) {
        // Current output pixel finishes in this cycle.
        val finalFixedOut16 = nextFixedAcc.asUInt(15, 0)
        val finalFloatOut16 = nextFloatAcc

        val finalOut16 = Mux(
          dataTypeReg === DATA_FLOAT16,
          finalFloatOut16,
          finalFixedOut16
        )

        outputBuf(outIndex(9, 0)) := finalOut16

        val watchComputeIdx =
          (outIndex < 4.U) ||
          (outIndex === 416.U) ||
          (outIndex === 704.U) ||
          (outIndex === 832.U) ||
          (outIndex >= 1020.U)

        when(watchComputeIdx) {
          printf("[COMPUTE_DBG] idx=%d row=%d col=%d dataType=%d out=%x macTerms=%d\n",
            outIndex,
            outRow,
            outCol,
            dataTypeReg,
            finalOut16,
            kernelElemsReg
          )
        }

        // Reset MAC state for the next output pixel.
        macKr  := 0.U
        macKc  := 0.U
        macIdx := 0.U

        fixedAcc := 0.S
        floatAcc := 0.U

        when(lastOutPixel) {
          outRow := 0.U
          outCol := 0.U

          computedReg := true.B
          resultReg := RET_SUCCESS

          printf("[MyConvAccel] COMPUTE complete\n")

          state := sRespond

        }.otherwise {
          when(outCol === (INPUT_SIZE - 1).U) {
            outCol := 0.U
            outRow := outRow + 1.U
          }.otherwise {
            outCol := outCol + 1.U
          }
        }

      }.otherwise {
        // Accumulate current term.
        fixedAcc := nextFixedAcc
        floatAcc := nextFloatAcc

        // Move to the next kernel term.
        macIdx := macIdx + 1.U

        when(lastKernelCol) {
          macKc := 0.U
          macKr := macKr + 1.U
        }.otherwise {
          macKc := macKc + 1.U
        }
      }
    }

    is(sStore) {
      // Write the 32x32 output matrix back to memory.
      // Both fixed16 and float16 use 16-bit elements.
      // Use one 64-bit full store for every four 16-bit output elements.

      val watchStoreIdx =
        (storeIdx < 16.U) ||
        (storeIdx === 416.U) ||
        (storeIdx === 704.U) ||
        (storeIdx === 832.U) ||
        (storeIdx >= 1008.U)

      when(storeIdx < INPUT_ELEMS.U) {
        when(!memInflight) {
          io.mem.req.valid := true.B

          // storeIdx is the starting 16-bit element index of this 64-bit store.
          // Byte offset = storeIdx * 2.
          io.mem.req.bits.addr := outputAddrReg + (storeIdx << 1)

          io.mem.req.bits.tag := 2.U
          io.mem.req.bits.cmd := M_XWR

          // size = log2(bytes). 3 means 8 bytes = 64-bit full store.
          io.mem.req.bits.size := 3.U

          io.mem.req.bits.signed := false.B

          // Pack four 16-bit outputs into one 64-bit word.
          io.mem.req.bits.data := Cat(
            outputBuf((storeIdx + 3.U)(9, 0)),
            outputBuf((storeIdx + 2.U)(9, 0)),
            outputBuf((storeIdx + 1.U)(9, 0)),
            outputBuf(storeIdx(9, 0))
          )

          when(io.mem.req.valid && !io.mem.req.ready && watchStoreIdx) {
            printf("[STORE_STALL] idx=%d addr=%x\n",
              storeIdx,
              outputAddrReg + (storeIdx << 1)
            )
          }

          when(io.mem.req.fire) {
            memInflight := true.B

            when(watchStoreIdx) {
              printf("[STORE_REQ] idx=%d addr=%x data=%x out0=%x out1=%x out2=%x out3=%x\n",
                storeIdx,
                outputAddrReg + (storeIdx << 1),
                io.mem.req.bits.data,
                outputBuf(storeIdx(9, 0)),
                outputBuf((storeIdx + 1.U)(9, 0)),
                outputBuf((storeIdx + 2.U)(9, 0)),
                outputBuf((storeIdx + 3.U)(9, 0))
              )
            }
          }
        }

        when(memInflight && io.mem.resp.valid) {
          memInflight := false.B

          when(watchStoreIdx) {
            printf("[STORE_RESP] idx=%d\n", storeIdx)
          }

          // Four 16-bit elements have now completed their store response.
          storeIdx := storeIdx + 4.U
        }

      }.otherwise {
        resultReg := RET_SUCCESS

        printf("[MyConvAccel] STORE complete storeIdx=%d\n", storeIdx)

        state := sRespond
      }
    }

    is(sRespond) {
      when(xdReg) {
        io.resp.valid := true.B

        when(io.resp.fire) {
          printf("[MyConvAccel] RESP fire rd=%d data=%x\n", rdReg, resultReg)
          state := sIdle
        }

      }.otherwise {
        printf("[MyConvAccel] RESP skipped because xd=0 data=%x\n", resultReg)
        state := sIdle
      }
    }
  }
}