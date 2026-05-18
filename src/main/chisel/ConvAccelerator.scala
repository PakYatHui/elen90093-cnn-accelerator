package myaccelerators

import chisel3._
import chisel3.util._

import org.chipsalliance.cde.config._
import org.chipsalliance.diplomacy.lazymodule._
import freechips.rocketchip.tile._
import freechips.rocketchip.rocket._
import hardfloat._

class MyConvAccel(opcodes: OpcodeSet)(implicit p: Parameters) extends LazyRoCC(opcodes) {
  println("DEBUG: Elaborating MyConvAccel streaming sliding-pipo row-parallel version")
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
  val MAX_TAGS         = 4

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

  // Preload load type encoding
  val PRELOAD_KERNEL = 0.U(2.W)
  val PRELOAD_BIAS   = 1.U(2.W)

  // Runtime tag type encoding
  val TAG_FREE        = 0.U(3.W)
  val TAG_LOAD_WINDOW = 1.U(3.W)
  val TAG_STORE_OUT   = 2.U(3.W)

  // Window buffer state encoding
  val BUF_FREE      = 0.U(2.W)
  val BUF_LOADING   = 1.U(2.W)
  val BUF_READY     = 2.U(2.W)
  val BUF_COMPUTING = 3.U(2.W)

  // Float16 parameters for HardFloat
  val FP16_EXP_WIDTH = 5
  val FP16_SIG_WIDTH = 11

  // FSM states
  val sIdle :: sDecode :: sConfig :: sKernel :: sLoad :: sCompute :: sStore :: sRespond :: Nil = Enum(8)
  val state = RegInit(sIdle)

  // ---------------------------------------------------------------------------
  // Queue metadata types
  // ---------------------------------------------------------------------------
  class WindowMeta extends Bundle {
    val bufId  = UInt(1.W)
    val outIdx = UInt(10.W)
    val row    = UInt(5.W)
    val col    = UInt(5.W)
  }

  class OutputMeta extends Bundle {
    val baseIdx = UInt(10.W)
    val data    = UInt(64.W)
  }

  val windowReadyQ = Module(new Queue(new WindowMeta, 2, pipe = true, flow = false))
  val outputQ      = Module(new Queue(new OutputMeta, 4, pipe = true, flow = false))

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
  // Streaming datapath storage
  // ---------------------------------------------------------------------------
  val kernelBuf = Reg(Vec(MAX_KERNEL_ELEMS, UInt(16.W)))
  val biasBuf   = RegInit(0.U(16.W))

  // Two 25-element PIPO / ping-pong window buffers.
  // Only one is written by the load stage while the other may be read by compute.
  val windowBuf = Reg(Vec(2, Vec(MAX_KERNEL_ELEMS, UInt(16.W))))
  val bufState  = RegInit(VecInit(Seq.fill(2)(BUF_FREE)))
  val bufFillCount = RegInit(VecInit(Seq.fill(2)(0.U(6.W))))
  val bufOutIdx = Reg(Vec(2, UInt(10.W)))
  val bufRow    = Reg(Vec(2, UInt(5.W)))
  val bufCol    = Reg(Vec(2, UInt(5.W)))

  // ---------------------------------------------------------------------------
  // DATA-stage preload bookkeeping: load kernel and optional scalar bias
  // ---------------------------------------------------------------------------
  val preloadKernelIdx = RegInit(0.U(6.W))
  val preloadInflight  = RegInit(false.B)
  val preloadType      = RegInit(PRELOAD_KERNEL)
  val preloadIndex     = RegInit(0.U(6.W))
  val preloadWide      = RegInit(false.B)
  val preloadBiasDone  = RegInit(true.B)

  // ---------------------------------------------------------------------------
  // Streaming load stage bookkeeping
  // ---------------------------------------------------------------------------
  val nextWindowIdx = RegInit(0.U(11.W))
  val loadActive    = RegInit(false.B)
  val loadBufId     = RegInit(0.U(1.W))
  val loadOutIdx    = RegInit(0.U(10.W))
  val loadRow       = RegInit(0.U(5.W))
  val loadCol       = RegInit(0.U(5.W))
  val loadKr        = RegInit(0.U(3.W))
  val loadKc        = RegInit(0.U(3.W))
  val loadElemIdx   = RegInit(0.U(5.W))

  // Completed window waiting to be pushed into windowReadyQ.
  val loadEnqPending = RegInit(false.B)
  val loadEnqBufId   = RegInit(0.U(1.W))

  // Sliding-window reuse bookkeeping.
  // reuse* points to the most recently completed window buffer. When the next
  // output is in the same row and col+1, the loader copies K*(K-1) values from
  // the previous window into the destination PIPO buffer and only reads the new
  // rightmost column from memory.
  val loadSlideMode = RegInit(false.B)
  val reuseValid    = RegInit(false.B)
  val reuseBufId    = RegInit(0.U(1.W))
  val reuseOutIdx   = RegInit(0.U(10.W))

  // ---------------------------------------------------------------------------
  // Streaming compute stage bookkeeping
  // ---------------------------------------------------------------------------
  val computeActive   = RegInit(false.B)
  val computeBufId    = RegInit(0.U(1.W))
  val computeOutIdx   = RegInit(0.U(10.W))
  val computeRow      = RegInit(0.U(5.W))
  val computeCol      = RegInit(0.U(5.W))
  // Row-parallel MAC counter. One cycle consumes one kernel row.
  // 1x1 needs 1 cycle/output, 3x3 needs 3 cycles/output, 5x5 needs 5 cycles/output.
  val computeMacRow   = RegInit(0.U(3.W))
  val computedOutCnt  = RegInit(0.U(11.W))

  val fixedAcc = RegInit(0.S(40.W))
  val floatAcc = RegInit(0.U(16.W))

  // 64-bit packed output store aggregator.
  // Four sequential 16-bit outputs are packed into one 64-bit outputQ entry.
  val packCount   = RegInit(0.U(2.W))
  val packBaseIdx = RegInit(0.U(10.W))
  val packData    = RegInit(0.U(64.W))

  val pendingPackValid = RegInit(false.B)
  val pendingPackBits  = Reg(new OutputMeta)

  // ---------------------------------------------------------------------------
  // Runtime tag table for memory response routing during sCompute
  // ---------------------------------------------------------------------------
  val tagValid     = RegInit(VecInit(Seq.fill(MAX_TAGS)(false.B)))
  val tagType      = RegInit(VecInit(Seq.fill(MAX_TAGS)(TAG_FREE)))
  val tagBufId     = Reg(Vec(MAX_TAGS, UInt(1.W)))
  val tagElemIdx   = Reg(Vec(MAX_TAGS, UInt(5.W)))
  val tagElemCount = Reg(Vec(MAX_TAGS, UInt(3.W)))
  val tagOutIdx    = Reg(Vec(MAX_TAGS, UInt(10.W)))

  val freeTagMask = VecInit(tagValid.map(v => !v)).asUInt
  val hasFreeTag  = freeTagMask.orR
  val freeTag     = PriorityEncoder(freeTagMask)
  val anyTagValid = tagValid.asUInt.orR

  // Simple round-robin preference between store and load on the single RoCC mem port.
  val preferStoreReg = RegInit(true.B)

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

  val validDataType =
    (rs2Reg(1, 0) === DATA_FIXED16) || (rs2Reg(1, 0) === DATA_FLOAT16)

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

  windowReadyQ.io.enq.valid := false.B
  windowReadyQ.io.enq.bits.bufId := 0.U
  windowReadyQ.io.enq.bits.outIdx := 0.U
  windowReadyQ.io.enq.bits.row := 0.U
  windowReadyQ.io.enq.bits.col := 0.U
  windowReadyQ.io.deq.ready := false.B

  outputQ.io.enq.valid := false.B
  outputQ.io.enq.bits.baseIdx := 0.U
  outputQ.io.enq.bits.data := 0.U
  outputQ.io.deq.ready := false.B

  // ---------------------------------------------------------------------------
  // HardFloat helpers
  // ---------------------------------------------------------------------------
  def fp16Mul(a: UInt, b: UInt): UInt = {
    val mul = Module(new MulRecFN(FP16_EXP_WIDTH, FP16_SIG_WIDTH))
    mul.io.a := recFNFromFN(FP16_EXP_WIDTH, FP16_SIG_WIDTH, a)
    mul.io.b := recFNFromFN(FP16_EXP_WIDTH, FP16_SIG_WIDTH, b)
    mul.io.roundingMode := 0.U(3.W)
    mul.io.detectTininess := 0.U(1.W)
    fNFromRecFN(FP16_EXP_WIDTH, FP16_SIG_WIDTH, mul.io.out)
  }

  def fp16Add(a: UInt, b: UInt): UInt = {
    val add = Module(new AddRecFN(FP16_EXP_WIDTH, FP16_SIG_WIDTH))
    add.io.subOp := false.B
    add.io.a := recFNFromFN(FP16_EXP_WIDTH, FP16_SIG_WIDTH, a)
    add.io.b := recFNFromFN(FP16_EXP_WIDTH, FP16_SIG_WIDTH, b)
    add.io.roundingMode := 0.U(3.W)
    add.io.detectTininess := 0.U(1.W)
    fNFromRecFN(FP16_EXP_WIDTH, FP16_SIG_WIDTH, add.io.out)
  }

  // ---------------------------------------------------------------------------
  // Row-parallel compute datapath wires
  // ---------------------------------------------------------------------------
  // The previous streaming version used one MAC term per cycle. That made the
  // lower bound for 5x5 equal to 1024 * 25 = 25600 cycles. This version uses up
  // to five lanes and consumes one complete kernel row per cycle:
  //   1x1 -> 1 cycle/output
  //   3x3 -> 3 cycles/output
  //   5x5 -> 5 cycles/output
  // The lane order is left-to-right within a row, preserving the same FP16
  // accumulation order as the software reference when it accumulates kr/kc.
  val laneActive = Wire(Vec(5, Bool()))
  val laneInput  = Wire(Vec(5, UInt(16.W)))
  val laneKernel = Wire(Vec(5, UInt(16.W)))

  for (i <- 0 until 5) {
    laneActive(i) := i.U < kernelSizeReg
    val laneIdx = (computeMacRow * kernelSizeReg) + i.U
    laneInput(i)  := Mux(laneActive(i), windowBuf(computeBufId)(laneIdx(4, 0)), 0.U(16.W))
    laneKernel(i) := Mux(laneActive(i), kernelBuf(laneIdx(4, 0)), 0.U(16.W))
  }

  // Fixed16: five parallel 8.8 MAC lanes, then one row-sum is accumulated.
  val fixedLaneTerms = Wire(Vec(5, SInt(40.W)))
  for (i <- 0 until 5) {
    val raw = (laneInput(i).asSInt * laneKernel(i).asSInt) >> 8
    fixedLaneTerms(i) := Mux(laneActive(i), raw.asSInt, 0.S(40.W))
  }
  val fixedRowSum01 = (fixedLaneTerms(0) + fixedLaneTerms(1)).asUInt(39, 0).asSInt
  val fixedRowSum23 = (fixedLaneTerms(2) + fixedLaneTerms(3)).asUInt(39, 0).asSInt
  val fixedRowSum04 = (fixedRowSum01 + fixedRowSum23 + fixedLaneTerms(4)).asUInt(39, 0).asSInt
  val nextFixedAccRow = (fixedAcc + fixedRowSum04).asUInt(39, 0).asSInt

  val fixedBiasTerm = Wire(SInt(40.W))
  fixedBiasTerm := Mux(biasEnableReg, biasBuf.asSInt, 0.S(16.W))
  val finalFixedAcc = (nextFixedAccRow + fixedBiasTerm).asUInt(39, 0).asSInt
  val finalFixedOut = finalFixedAcc.asUInt(15, 0)

  // Float16: five parallel multipliers followed by a left-to-right add chain.
  // The add chain gives the same rounding order as scalar sequential kc order.
  val floatZero = 0.U(16.W)
  val floatProducts = Wire(Vec(5, UInt(16.W)))
  for (i <- 0 until 5) {
    val p = fp16Mul(laneInput(i), laneKernel(i))
    floatProducts(i) := Mux(laneActive(i), p, floatZero)
  }
  val floatAcc0 = fp16Add(floatAcc, floatProducts(0))
  val floatAcc1 = Mux(kernelSizeReg > 1.U, fp16Add(floatAcc0, floatProducts(1)), floatAcc0)
  val floatAcc2 = Mux(kernelSizeReg > 2.U, fp16Add(floatAcc1, floatProducts(2)), floatAcc1)
  val floatAcc3 = Mux(kernelSizeReg > 3.U, fp16Add(floatAcc2, floatProducts(3)), floatAcc2)
  val nextFloatAccRow = Mux(kernelSizeReg > 4.U, fp16Add(floatAcc3, floatProducts(4)), floatAcc3)

  val finalFloatOut = Mux(biasEnableReg, fp16Add(nextFloatAccRow, biasBuf), nextFloatAccRow)

  val finalOut16 = Mux(dataTypeReg === DATA_FLOAT16, finalFloatOut, finalFixedOut)
  val lastMacRow = computeMacRow === (kernelSizeReg - 1.U)

  // ---------------------------------------------------------------------------
  // Window-load address generation wires
  // ---------------------------------------------------------------------------
  val loadRadius = kernelSizeReg >> 1
  val loadRowS = loadRow.zext + loadKr.zext - loadRadius.zext
  val loadColS = loadCol.zext + loadKc.zext - loadRadius.zext
  val loadColEndS = loadCol.zext + (loadKc + 3.U).zext - loadRadius.zext

  val loadRowValid = loadRowS >= 0.S && loadRowS < INPUT_SIZE.S
  val loadColValid = loadColS >= 0.S && loadColS < INPUT_SIZE.S
  val loadGroupColValid = loadColEndS >= 0.S && loadColEndS < INPUT_SIZE.S

  val loadScalarValid = loadRowValid && loadColValid
  val loadMemIndex = (loadRowS.asUInt << 5) + loadColS.asUInt
  val loadMemAddr = inputAddrReg + (loadMemIndex << 1)

  // A 64-bit HellaCache load must be naturally aligned.
  // 5x5 windows may start at arbitrary input columns, so many legal window
  // positions are only 16-bit aligned. Falling back to scalar loads avoids
  // SimpleHellaCacheIF alignment exceptions.
  val loadMemAddrAligned64 = loadMemAddr(2, 0) === 0.U

  val loadCanWide =
    !loadSlideMode &&
    (loadKc + 3.U) < kernelSizeReg &&
    loadRowValid && loadColValid && loadGroupColValid &&
    loadMemAddrAligned64

  // ---------------------------------------------------------------------------
  // Helper method for advancing the logical window element cursor
  // ---------------------------------------------------------------------------
  def advanceLoadCursor(count: UInt): Unit = {
    when(loadSlideMode) {
      // Sliding mode only loads the new rightmost column. The next element is
      // the same kernel column in the next kernel row, so its compact KxK index
      // advances by kernelSizeReg instead of by one.
      loadElemIdx := loadElemIdx + kernelSizeReg
      loadKr := loadKr + 1.U
      loadKc := kernelSizeReg - 1.U
    }.otherwise {
      val nextKc = loadKc + count
      loadElemIdx := loadElemIdx + count
      when(nextKc >= kernelSizeReg) {
        loadKc := 0.U
        loadKr := loadKr + 1.U
      }.otherwise {
        loadKc := nextKc
      }
    }
  }

  def finishWindowIfFilled(buf: UInt, newCount: UInt): Unit = {
    when(newCount >= kernelElemsReg) {
      bufState(buf) := BUF_READY
      loadActive := false.B
      loadEnqPending := true.B
      loadEnqBufId := buf
      reuseValid := true.B
      reuseBufId := buf
      reuseOutIdx := bufOutIdx(buf)
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

          preloadKernelIdx := 0.U
          preloadInflight := false.B
          preloadBiasDone := !biasEnableReg
          loadedReg := false.B
          computedReg := false.B

          state := sLoad
        }.otherwise {
          resultReg := RET_ERROR
          state := sRespond
        }

      }.elsewhen(doCompute) {
        when(configuredReg && kernelConfiguredReg && loadedReg) {
          // Reset streaming control state for a new run.
          nextWindowIdx := 0.U
          loadActive := false.B
          loadEnqPending := false.B
          loadSlideMode := false.B
          reuseValid := false.B
          computeActive := false.B
          computeMacRow := 0.U
          computedOutCnt := 0.U
          fixedAcc := 0.S
          floatAcc := 0.U
          packCount := 0.U
          packBaseIdx := 0.U
          packData := 0.U
          pendingPackValid := false.B
          preferStoreReg := true.B

          for (i <- 0 until 2) {
            bufState(i) := BUF_FREE
            bufFillCount(i) := 0.U
          }
          for (i <- 0 until MAX_TAGS) {
            tagValid(i) := false.B
            tagType(i) := TAG_FREE
          }

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
      // DATA stage now preloads only kernel weights and optional scalar bias.
      // Input windows are loaded later inside sCompute using PIPO buffers.
      when(!preloadInflight) {
        when(preloadKernelIdx < kernelElemsReg) {
          val kernelWideLoad = (preloadKernelIdx + 3.U) < kernelElemsReg

          io.mem.req.valid := true.B
          io.mem.req.bits.addr := kernelAddrReg + (preloadKernelIdx << 1)
          io.mem.req.bits.tag := 0.U
          io.mem.req.bits.cmd := M_XRD
          io.mem.req.bits.size := Mux(kernelWideLoad, 3.U, 1.U)
          io.mem.req.bits.signed := true.B
          io.mem.req.bits.dprv := dprvReg

          when(io.mem.req.fire) {
            preloadInflight := true.B
            preloadType := PRELOAD_KERNEL
            preloadIndex := preloadKernelIdx
            preloadWide := kernelWideLoad
          }

        }.elsewhen(biasEnableReg && !preloadBiasDone) {
          io.mem.req.valid := true.B
          io.mem.req.bits.addr := biasAddrReg
          io.mem.req.bits.tag := 1.U
          io.mem.req.bits.cmd := M_XRD
          io.mem.req.bits.size := 1.U
          io.mem.req.bits.signed := true.B
          io.mem.req.bits.dprv := dprvReg

          when(io.mem.req.fire) {
            preloadInflight := true.B
            preloadType := PRELOAD_BIAS
            preloadIndex := 0.U
            preloadWide := false.B
          }

        }.otherwise {
          loadedReg := true.B
          computedReg := false.B
          resultReg := RET_SUCCESS
          state := sRespond
        }
      }

      when(io.mem.resp.valid) {
        val loadedData = io.mem.resp.bits.data

        when(preloadType === PRELOAD_KERNEL) {
          kernelBuf(preloadIndex(4, 0)) := loadedData(15, 0)
          when(preloadWide) {
            kernelBuf((preloadIndex + 1.U)(4, 0)) := loadedData(31, 16)
            kernelBuf((preloadIndex + 2.U)(4, 0)) := loadedData(47, 32)
            kernelBuf((preloadIndex + 3.U)(4, 0)) := loadedData(63, 48)
            preloadKernelIdx := preloadIndex + 4.U
          }.otherwise {
            preloadKernelIdx := preloadIndex + 1.U
          }
        }.otherwise {
          biasBuf := loadedData(15, 0)
          preloadBiasDone := true.B
        }

        preloadInflight := false.B
      }
    }

    is(sCompute) {
      // -----------------------------------------------------------------------
      // 1. Push a completed load buffer into windowReadyQ when possible.
      // -----------------------------------------------------------------------
      when(loadEnqPending) {
        windowReadyQ.io.enq.valid := true.B
        windowReadyQ.io.enq.bits.bufId := loadEnqBufId
        windowReadyQ.io.enq.bits.outIdx := bufOutIdx(loadEnqBufId)
        windowReadyQ.io.enq.bits.row := bufRow(loadEnqBufId)
        windowReadyQ.io.enq.bits.col := bufCol(loadEnqBufId)

        when(windowReadyQ.io.enq.fire) {
          loadEnqPending := false.B
        }
      }

      // -----------------------------------------------------------------------
      // 2. Enqueue pending packed output first, if outputQ was previously full.
      // -----------------------------------------------------------------------
      when(pendingPackValid) {
        outputQ.io.enq.valid := true.B
        outputQ.io.enq.bits := pendingPackBits
        when(outputQ.io.enq.fire) {
          pendingPackValid := false.B
        }
      }

      // -----------------------------------------------------------------------
      // 3. Start loading a new output window into a free PIPO buffer.
      // -----------------------------------------------------------------------
      val free0 = bufState(0) === BUF_FREE
      val free1 = bufState(1) === BUF_FREE
      val hasFreeBuf = free0 || free1

      // A sliding reuse is legal only for the next column in the same row. The
      // destination PIPO buffer must be different from the source buffer so the
      // load stage never overwrites the buffer used by compute/source reuse.
      val nextOutIdx10 = nextWindowIdx(9, 0)
      val nextCol5 = nextWindowIdx(4, 0)
      val reuseIsPrev = reuseValid && nextCol5 =/= 0.U && reuseOutIdx === (nextOutIdx10 - 1.U)
      val slideDestAvailable = Mux(reuseBufId === 0.U, free1, free0)
      val useSlideForNew = reuseIsPrev && slideDestAvailable && (kernelSizeReg =/= 1.U)
      val selectedBuf = Mux(useSlideForNew, ~reuseBufId, Mux(free0, 0.U(1.W), 1.U(1.W)))

      when(!loadActive && !loadEnqPending && nextWindowIdx < INPUT_ELEMS.U && hasFreeBuf) {
        val copiedCount = Mux(kernelSizeReg === 5.U, 20.U(6.W), Mux(kernelSizeReg === 3.U, 6.U(6.W), 0.U(6.W)))
        val initialFill = Mux(useSlideForNew, copiedCount, 0.U(6.W))
        val firstKc = Mux(useSlideForNew, kernelSizeReg - 1.U, 0.U)
        val firstElem = Mux(useSlideForNew, kernelSizeReg - 1.U, 0.U)

        loadActive := true.B
        loadSlideMode := useSlideForNew
        loadBufId := selectedBuf
        loadOutIdx := nextOutIdx10
        loadRow := nextWindowIdx(9, 5)
        loadCol := nextCol5
        loadKr := 0.U
        loadKc := firstKc
        loadElemIdx := firstElem

        bufState(selectedBuf) := BUF_LOADING
        bufFillCount(selectedBuf) := initialFill
        bufOutIdx(selectedBuf) := nextOutIdx10
        bufRow(selectedBuf) := nextWindowIdx(9, 5)
        bufCol(selectedBuf) := nextCol5

        // PIPO sliding copy. The destination buffer receives the left K-1
        // columns from the previous window. The loader then fills only the new
        // rightmost column. This preserves the pipeline-buffer design: load
        // writes selectedBuf while compute may read reuseBufId.
        when(useSlideForNew) {
          when(kernelSizeReg === 3.U) {
            windowBuf(selectedBuf)(0) := windowBuf(reuseBufId)(1)
            windowBuf(selectedBuf)(1) := windowBuf(reuseBufId)(2)
            windowBuf(selectedBuf)(3) := windowBuf(reuseBufId)(4)
            windowBuf(selectedBuf)(4) := windowBuf(reuseBufId)(5)
            windowBuf(selectedBuf)(6) := windowBuf(reuseBufId)(7)
            windowBuf(selectedBuf)(7) := windowBuf(reuseBufId)(8)
          }.elsewhen(kernelSizeReg === 5.U) {
            windowBuf(selectedBuf)(0)  := windowBuf(reuseBufId)(1)
            windowBuf(selectedBuf)(1)  := windowBuf(reuseBufId)(2)
            windowBuf(selectedBuf)(2)  := windowBuf(reuseBufId)(3)
            windowBuf(selectedBuf)(3)  := windowBuf(reuseBufId)(4)
            windowBuf(selectedBuf)(5)  := windowBuf(reuseBufId)(6)
            windowBuf(selectedBuf)(6)  := windowBuf(reuseBufId)(7)
            windowBuf(selectedBuf)(7)  := windowBuf(reuseBufId)(8)
            windowBuf(selectedBuf)(8)  := windowBuf(reuseBufId)(9)
            windowBuf(selectedBuf)(10) := windowBuf(reuseBufId)(11)
            windowBuf(selectedBuf)(11) := windowBuf(reuseBufId)(12)
            windowBuf(selectedBuf)(12) := windowBuf(reuseBufId)(13)
            windowBuf(selectedBuf)(13) := windowBuf(reuseBufId)(14)
            windowBuf(selectedBuf)(15) := windowBuf(reuseBufId)(16)
            windowBuf(selectedBuf)(16) := windowBuf(reuseBufId)(17)
            windowBuf(selectedBuf)(17) := windowBuf(reuseBufId)(18)
            windowBuf(selectedBuf)(18) := windowBuf(reuseBufId)(19)
            windowBuf(selectedBuf)(20) := windowBuf(reuseBufId)(21)
            windowBuf(selectedBuf)(21) := windowBuf(reuseBufId)(22)
            windowBuf(selectedBuf)(22) := windowBuf(reuseBufId)(23)
            windowBuf(selectedBuf)(23) := windowBuf(reuseBufId)(24)
          }
        }

        nextWindowIdx := nextWindowIdx + 1.U
      }

      // -----------------------------------------------------------------------
      // 4. Start compute from a ready window buffer.
      // -----------------------------------------------------------------------
      when(!computeActive && !pendingPackValid && windowReadyQ.io.deq.valid) {
        windowReadyQ.io.deq.ready := true.B

        when(windowReadyQ.io.deq.fire) {
          computeActive := true.B
          computeBufId := windowReadyQ.io.deq.bits.bufId
          computeOutIdx := windowReadyQ.io.deq.bits.outIdx
          computeRow := windowReadyQ.io.deq.bits.row
          computeCol := windowReadyQ.io.deq.bits.col
          computeMacRow := 0.U
          fixedAcc := 0.S
          floatAcc := 0.U
          bufState(windowReadyQ.io.deq.bits.bufId) := BUF_COMPUTING
        }
      }

      // -----------------------------------------------------------------------
      // 5. Row-parallel MAC compute: one kernel row per cycle.
      // -----------------------------------------------------------------------
      when(computeActive && !pendingPackValid) {
        when(lastMacRow) {
          // Current output pixel finishes this cycle.
          val completedOut = finalOut16

          // Pack four sequential 16-bit outputs into one 64-bit output store.
          when(packCount === 0.U) {
            packBaseIdx := computeOutIdx
            packData := completedOut
            packCount := 1.U
          }.elsewhen(packCount === 1.U) {
            packData := packData | (completedOut << 16)
            packCount := 2.U
          }.elsewhen(packCount === 2.U) {
            packData := packData | (completedOut << 32)
            packCount := 3.U
          }.otherwise {
            val fullPack = packData | (completedOut << 48)

            when(!pendingPackValid) {
              outputQ.io.enq.valid := true.B
              outputQ.io.enq.bits.baseIdx := packBaseIdx
              outputQ.io.enq.bits.data := fullPack

              when(outputQ.io.enq.fire) {
                packCount := 0.U
                packData := 0.U
              }.otherwise {
                pendingPackValid := true.B
                pendingPackBits.baseIdx := packBaseIdx
                pendingPackBits.data := fullPack
                packCount := 0.U
                packData := 0.U
              }
            }
          }

          bufState(computeBufId) := BUF_FREE
          computeActive := false.B
          computeMacRow := 0.U
          fixedAcc := 0.S
          floatAcc := 0.U
          computedOutCnt := computedOutCnt + 1.U

        }.otherwise {
          fixedAcc := nextFixedAccRow
          floatAcc := nextFloatAccRow
          computeMacRow := computeMacRow + 1.U
        }
      }

      // -----------------------------------------------------------------------
      // 6. Runtime memory response routing using tag table.
      // -----------------------------------------------------------------------
      when(io.mem.resp.valid) {
        val rTag = io.mem.resp.bits.tag(log2Ceil(MAX_TAGS)-1, 0)
        val rData = io.mem.resp.bits.data

        when(tagValid(rTag)) {
          when(tagType(rTag) === TAG_LOAD_WINDOW) {
            val b = tagBufId(rTag)
            val e = tagElemIdx(rTag)
            val c = tagElemCount(rTag)

            when(c >= 1.U) { windowBuf(b)(e) := rData(15, 0) }
            when(c >= 2.U) { windowBuf(b)((e + 1.U)(4, 0)) := rData(31, 16) }
            when(c >= 3.U) { windowBuf(b)((e + 2.U)(4, 0)) := rData(47, 32) }
            when(c >= 4.U) { windowBuf(b)((e + 3.U)(4, 0)) := rData(63, 48) }

            val newFill = bufFillCount(b) + c
            bufFillCount(b) := newFill
            finishWindowIfFilled(b, newFill)
          }

          // Store responses only free the tag.
          tagValid(rTag) := false.B
          tagType(rTag) := TAG_FREE
        }
      }

      // -----------------------------------------------------------------------
      // 7. Runtime memory scheduler.
      //    Single io.mem.req port: choose either STORE_OUT or LOAD_WINDOW.
      //    To avoid same-cycle tag state hazards, do not issue a new request in
      //    a cycle where a memory response is being routed.
      // -----------------------------------------------------------------------
      when(!io.mem.resp.valid) {
        // Local zero-padding does not consume the memory port.
        when(loadActive && loadElemIdx < kernelElemsReg && !loadScalarValid) {
          windowBuf(loadBufId)(loadElemIdx) := 0.U

          val newFill = bufFillCount(loadBufId) + 1.U
          bufFillCount(loadBufId) := newFill
          advanceLoadCursor(1.U)
          finishWindowIfFilled(loadBufId, newFill)

        }.otherwise {
          val canIssueStore = outputQ.io.deq.valid && hasFreeTag
          val canIssueLoad = loadActive && (loadElemIdx < kernelElemsReg) && loadScalarValid && hasFreeTag

          val chooseStore = canIssueStore && (!canIssueLoad || preferStoreReg)
          val chooseLoad = canIssueLoad && (!canIssueStore || !preferStoreReg)

          when(chooseStore) {
            io.mem.req.valid := true.B
            io.mem.req.bits.addr := outputAddrReg + (outputQ.io.deq.bits.baseIdx << 1)
            io.mem.req.bits.tag := freeTag
            io.mem.req.bits.cmd := M_XWR
            io.mem.req.bits.size := 3.U       // 8 bytes = 64-bit full-width store
            io.mem.req.bits.signed := false.B
            io.mem.req.bits.data := outputQ.io.deq.bits.data
            io.mem.req.bits.dprv := dprvReg

            outputQ.io.deq.ready := io.mem.req.ready

            when(io.mem.req.fire) {
              tagValid(freeTag) := true.B
              tagType(freeTag) := TAG_STORE_OUT
              tagOutIdx(freeTag) := outputQ.io.deq.bits.baseIdx
              preferStoreReg := false.B
            }

          }.elsewhen(chooseLoad) {
            val wideCount = Mux(loadCanWide, 4.U(3.W), 1.U(3.W))

            io.mem.req.valid := true.B
            io.mem.req.bits.addr := loadMemAddr
            io.mem.req.bits.tag := freeTag
            io.mem.req.bits.cmd := M_XRD
            io.mem.req.bits.size := Mux(loadCanWide, 3.U, 1.U)
            io.mem.req.bits.signed := true.B
            io.mem.req.bits.dprv := dprvReg

            when(io.mem.req.fire) {
              tagValid(freeTag) := true.B
              tagType(freeTag) := TAG_LOAD_WINDOW
              tagBufId(freeTag) := loadBufId
              tagElemIdx(freeTag) := loadElemIdx
              tagElemCount(freeTag) := wideCount

              advanceLoadCursor(wideCount)
              preferStoreReg := true.B
            }
          }
        }
      }

      // -----------------------------------------------------------------------
      // 8. Done condition: all windows issued, all outputs computed and packed,
      //    outputQ drained, all store acks returned, no active buffers/requests.
      // -----------------------------------------------------------------------
      val allBuffersFree = (bufState(0) === BUF_FREE) && (bufState(1) === BUF_FREE)
      val allWindowsIssued = nextWindowIdx >= INPUT_ELEMS.U
      val allOutputsComputed = computedOutCnt >= INPUT_ELEMS.U
      val queuesEmpty = !windowReadyQ.io.deq.valid && !outputQ.io.deq.valid
      val noPendingWork = !loadActive && !loadEnqPending && !computeActive && !pendingPackValid
      val noPackRemainder = packCount === 0.U

      when(allWindowsIssued && allOutputsComputed && allBuffersFree && queuesEmpty && noPendingWork && noPackRemainder && !anyTagValid) {
        computedReg := true.B
        resultReg := RET_SUCCESS
        state := sRespond
      }
    }

    is(sStore) {
      // Compatibility barrier.
      // In this streaming design, output stores are issued during COMPUTE.
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
