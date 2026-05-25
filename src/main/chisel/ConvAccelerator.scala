package myaccelerators

import chisel3._
import chisel3.util._

import org.chipsalliance.cde.config._
import org.chipsalliance.diplomacy.lazymodule._
import freechips.rocketchip.tile._
import freechips.rocketchip.rocket._
import hardfloat._

// =============================================================================
// Phase 7B pipelined accelerator with vertical row reuse.
//
// External RoCC command FSM is intentionally kept compatible with Phase 6B:
//   sIdle -> sDecode -> sConfig/sKernel/sLoad/sCompute/sStore/sRespond
//
// Internal tile execution is changed from one sequential fixedTileState FSM into
// three independent engines inside sCompute:
//   1. Load engine    : loads the next tile into a free ping-pong tile buffer
//   2. Compute engine : computes a ready tile from another tile buffer
//   3. Store engine   : stores packed 4-output results from outputQ
//
// This version restores vertical row reuse across the ping-pong tile buffers.
// For row N in the same 4-column block, rows 1..K-1 from tile N-1 are copied
// into rows 0..K-2 of the destination buffer, then only the newest bottom row
// is loaded from memory. This reduces input memory traffic while keeping the
// load/compute/store pipeline structure.
// =============================================================================

class MyConvAccel(opcodes: OpcodeSet)(implicit p: Parameters) extends LazyRoCC(opcodes) {
  println("DEBUG: Elaborating MyConvAccel Phase 7B pipelined vertical-reuse version")
  override lazy val module = new MyConvAccelModule(this)
}

class MyConvAccelModule(outer: MyConvAccel)(implicit p: Parameters)
  extends LazyRoCCModuleImp(outer)
  with HasCoreParameters {

  val cmd = Queue(io.cmd, 1)

  val INPUT_SIZE       = 32
  val INPUT_ELEMS      = INPUT_SIZE * INPUT_SIZE

  val MAX_KERNEL_SIZE  = 5
  val MAX_KERNEL_ELEMS = MAX_KERNEL_SIZE * MAX_KERNEL_SIZE

  val TILE_OUTPUTS     = 4
  val MAX_TILE_WIDTH   = MAX_KERNEL_SIZE + TILE_OUTPUTS - 1
  val TOTAL_TILES      = INPUT_ELEMS / TILE_OUTPUTS

  val RET_ERROR   = 0.U(xLen.W)
  val RET_SUCCESS = 1.U(xLen.W)

  val DATA_FIXED16 = 0.U(2.W)
  val DATA_FLOAT16 = 1.U(2.W)

  val FP16_EXP_WIDTH = 5
  val FP16_SIG_WIDTH = 11

  val FUNCT_CONFIG  = 0.U(7.W)
  val FUNCT_DATA    = 1.U(7.W)
  val FUNCT_COMPUTE = 2.U(7.W)
  val FUNCT_STORE   = 3.U(7.W)
  val FUNCT_KERNEL  = 4.U(7.W)

  val PRELOAD_KERNEL = 0.U(1.W)
  val PRELOAD_BIAS   = 1.U(1.W)

  // The external command FSM is kept unchanged for software compatibility.
  val sIdle :: sDecode :: sConfig :: sKernel :: sLoad :: sCompute :: sStore :: sRespond :: Nil = Enum(8)
  val state = RegInit(sIdle)

  // Internal pipelined engines used only inside sCompute.
  val loadIdle :: loadRun :: loadEnq :: Nil = Enum(3)
  val loadState = RegInit(loadIdle)

  val computeIdle :: computeRun :: Nil = Enum(2)
  val computeState = RegInit(computeIdle)

  val bufFree :: bufLoading :: bufReady :: bufComputing :: Nil = Enum(4)

  val memNone :: memLoadTile :: memStoreOut :: Nil = Enum(3)

  class TileDesc extends Bundle {
    val row   = UInt(6.W)
    val col   = UInt(6.W)
    val bufId = UInt(1.W)
  }

  class StoreDesc(val coreXLen: Int) extends Bundle {
    val addr = UInt(coreXLen.W)
    val data = UInt(64.W)
  }

  val loadToComputeQ = Module(new Queue(new TileDesc, 4))
  val outputQ        = Module(new Queue(new StoreDesc(xLen), 8))

  val functReg = RegInit(0.U(7.W))
  val rs1Reg   = RegInit(0.U(xLen.W))
  val rs2Reg   = RegInit(0.U(xLen.W))
  val rdReg    = RegInit(0.U(5.W))
  val dprvReg  = RegInit(0.U(2.W))
  val xdReg    = RegInit(false.B)

  val inputAddrReg   = RegInit(0.U(xLen.W))
  val kernelAddrReg  = RegInit(0.U(xLen.W))
  val outputAddrReg  = RegInit(0.U(xLen.W))
  val biasAddrReg    = RegInit(0.U(xLen.W))
  val kernelSizeReg  = RegInit(0.U(3.W))
  val kernelElemsReg = RegInit(0.U(6.W))
  val dataTypeReg    = RegInit(DATA_FIXED16)
  val biasEnableReg  = RegInit(false.B)

  val configuredReg       = RegInit(false.B)
  val kernelConfiguredReg = RegInit(false.B)
  val loadedReg           = RegInit(false.B)
  val computedReg         = RegInit(false.B)

  val resultReg = RegInit(RET_ERROR)

  val kernelBuf = Reg(Vec(MAX_KERNEL_ELEMS, UInt(16.W)))
  val biasBuf   = RegInit(0.U(16.W))

  // Two tile buffers allow loading tile N+1 while computing tile N.
  val tileBuf = Reg(Vec(2, Vec(MAX_KERNEL_SIZE, Vec(MAX_TILE_WIDTH, UInt(16.W)))))
  val tileBufState = RegInit(VecInit(Seq.fill(2)(bufFree)))

  // Metadata for vertical row reuse between consecutive rows in the same
  // 4-output column block. The previous tile buffer remains readable until it
  // is selected as the destination for a later load.
  val prevTileValidReg = RegInit(false.B)
  val prevTileRowReg   = RegInit(0.U(6.W))
  val prevTileColReg   = RegInit(0.U(6.W))
  val prevTileBufIdReg = RegInit(0.U(1.W))
  val loadReuseActiveReg = RegInit(false.B)

  val preloadIdx      = RegInit(0.U(6.W))
  val preloadInflight = RegInit(false.B)
  val preloadType     = RegInit(PRELOAD_KERNEL)
  val biasLoadedReg   = RegInit(true.B)

  // Tile generator. Traversal remains column-block-major: col 0 rows 0..31,
  // then col 4 rows 0..31, etc.
  val tileGenRowReg = RegInit(0.U(6.W))
  val tileGenColReg = RegInit(0.U(6.W))

  val tilesIssuedCnt  = RegInit(0.U(9.W))
  val tilesLoadedCnt  = RegInit(0.U(9.W))
  val tilesComputedCnt = RegInit(0.U(9.W))
  val tilesStoredCnt  = RegInit(0.U(9.W))

  // Load engine registers.
  val loadBaseRowReg = RegInit(0.U(6.W))
  val loadBaseColReg = RegInit(0.U(6.W))
  val loadBufIdReg   = RegInit(0.U(1.W))
  val tileLoadRow    = RegInit(0.U(3.W))
  val tileLoadCol    = RegInit(0.U(4.W))
  val tileLoadCnt    = RegInit(0.U(6.W))

  // Metadata for the single outstanding tile load request.
  val memInflight = RegInit(false.B)
  val memKindReg  = RegInit(memNone)
  val memLoadBufIdReg = RegInit(0.U(1.W))
  val memLoadRowReg   = RegInit(0.U(3.W))
  val memLoadColReg   = RegInit(0.U(4.W))
  val memLoadWideReg  = RegInit(false.B)
  val memLoadWideCountReg = RegInit(0.U(3.W))

  // Round-robin hint for the single RoCC memory request port.
  val memPreferStore = RegInit(false.B)

  // Compute engine registers.
  val computeBaseRowReg = RegInit(0.U(6.W))
  val computeBaseColReg = RegInit(0.U(6.W))
  val computeBufIdReg   = RegInit(0.U(1.W))
  val computeMacRow     = RegInit(0.U(3.W))

  val tileAcc      = RegInit(VecInit(Seq.fill(TILE_OUTPUTS)(0.S(40.W))))
  val tileFloatAcc = RegInit(VecInit(Seq.fill(TILE_OUTPUTS)(0.U(16.W))))
  val tileOutReg   = RegInit(VecInit(Seq.fill(TILE_OUTPUTS)(0.U(16.W))))

  val doConfig  = functReg === FUNCT_CONFIG
  val doData    = functReg === FUNCT_DATA
  val doCompute = functReg === FUNCT_COMPUTE
  val doStore   = functReg === FUNCT_STORE
  val doKernel  = functReg === FUNCT_KERNEL

  val validKernelSize =
    (rs1Reg === 1.U) || (rs1Reg === 3.U) || (rs1Reg === 5.U)

  val validDataType =
    (rs2Reg(1, 0) === DATA_FIXED16) ||
    (rs2Reg(1, 0) === DATA_FLOAT16)

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

  loadToComputeQ.io.enq.valid := false.B
  loadToComputeQ.io.enq.bits := 0.U.asTypeOf(new TileDesc)
  loadToComputeQ.io.deq.ready := false.B

  outputQ.io.enq.valid := false.B
  outputQ.io.enq.bits := 0.U.asTypeOf(new StoreDesc(xLen))
  outputQ.io.deq.ready := false.B

  // Multiply two raw FP16 values and return a raw FP16 result.
  def fp16Mul(a: UInt, b: UInt): UInt = {
    val mul = Module(new MulRecFN(FP16_EXP_WIDTH, FP16_SIG_WIDTH))

    mul.io.a := recFNFromFN(FP16_EXP_WIDTH, FP16_SIG_WIDTH, a)
    mul.io.b := recFNFromFN(FP16_EXP_WIDTH, FP16_SIG_WIDTH, b)
    mul.io.roundingMode := 0.U(3.W)
    mul.io.detectTininess := 0.U(1.W)

    fNFromRecFN(FP16_EXP_WIDTH, FP16_SIG_WIDTH, mul.io.out)
  }

  // Add two raw FP16 values and return a raw FP16 result.
  def fp16Add(a: UInt, b: UInt): UInt = {
    val add = Module(new AddRecFN(FP16_EXP_WIDTH, FP16_SIG_WIDTH))

    add.io.subOp := false.B
    add.io.a := recFNFromFN(FP16_EXP_WIDTH, FP16_SIG_WIDTH, a)
    add.io.b := recFNFromFN(FP16_EXP_WIDTH, FP16_SIG_WIDTH, b)
    add.io.roundingMode := 0.U(3.W)
    add.io.detectTininess := 0.U(1.W)

    fNFromRecFN(FP16_EXP_WIDTH, FP16_SIG_WIDTH, add.io.out)
  }

  val tileRadius = kernelSizeReg >> 1
  val tileWidth  = kernelSizeReg +& (TILE_OUTPUTS - 1).U(3.W)
  val tileElems  = (kernelSizeReg * tileWidth)(5, 0)
  val tileLoadDone = Mux(
    loadReuseActiveReg,
    tileLoadRow >= kernelSizeReg,
    tileLoadCnt >= tileElems
  )

  val loadInRowS = loadBaseRowReg.zext + tileLoadRow.zext - tileRadius.zext
  val loadInColS = loadBaseColReg.zext + tileLoadCol.zext - tileRadius.zext

  val tileLoadInputValid =
    loadInRowS >= 0.S && loadInRowS < INPUT_SIZE.S &&
    loadInColS >= 0.S && loadInColS < INPUT_SIZE.S

  val tileLoadMemIndex = (loadInRowS.asUInt << 5) + loadInColS.asUInt
  val tileLoadMemAddr  = inputAddrReg + (tileLoadMemIndex << 1)

  val tileLoadColsRemaining = tileWidth - tileLoadCol
  val tileLoadWideCount = Mux(
    tileLoadColsRemaining >= 4.U,
    4.U(3.W),
    tileLoadColsRemaining(2, 0)
  )

  val tileLoadCanWide =
    tileLoadInputValid &&
    (tileLoadCol < tileWidth) &&
    (tileLoadWideCount =/= 0.U) &&
    (loadInColS >= 0.S) &&
    (loadInColS <= (INPUT_SIZE - 4).S) &&
    (loadInColS.asUInt(1, 0) === 0.U)

  val biasTerm = Wire(SInt(40.W))
  biasTerm := Mux(biasEnableReg, biasBuf.asSInt, 0.S(40.W))

  // Fixed16 row-level datapath.
  val tileTerms    = Wire(Vec(TILE_OUTPUTS, Vec(MAX_KERNEL_SIZE, SInt(40.W))))
  val tileRowSum   = Wire(Vec(TILE_OUTPUTS, SInt(40.W)))
  val tileNextAcc  = Wire(Vec(TILE_OUTPUTS, SInt(40.W)))
  val fixedFinalOut = Wire(Vec(TILE_OUTPUTS, UInt(16.W)))

  for (o <- 0 until TILE_OUTPUTS) {
    for (kc <- 0 until MAX_KERNEL_SIZE) {
      val validKc = kc.U < kernelSizeReg
      val tileCol = (kc + o).U(4.W)
      val kernelElem = ((computeMacRow * kernelSizeReg) +& kc.U(3.W))(4, 0)

      val inVal  = tileBuf(computeBufIdReg)(computeMacRow)(tileCol(2, 0)).asSInt
      val kerVal = kernelBuf(kernelElem).asSInt

      val rawProduct = inVal * kerVal
      val scaledTerm = (rawProduct >> 8).asSInt

      tileTerms(o)(kc) := Mux(validKc, scaledTerm, 0.S(40.W))
    }

    val sum01 = (tileTerms(o)(0) + tileTerms(o)(1)).asUInt(39, 0).asSInt
    val sum23 = (tileTerms(o)(2) + tileTerms(o)(3)).asUInt(39, 0).asSInt

    tileRowSum(o)   := (sum01 + sum23 + tileTerms(o)(4)).asUInt(39, 0).asSInt
    tileNextAcc(o)  := (tileAcc(o) + tileRowSum(o)).asUInt(39, 0).asSInt
    fixedFinalOut(o) := (tileNextAcc(o) + biasTerm).asUInt(15, 0)
  }

  // Float16 row-level datapath.
  val tileFloatTerms    = Wire(Vec(TILE_OUTPUTS, Vec(MAX_KERNEL_SIZE, UInt(16.W))))
  val tileFloatNextAcc  = Wire(Vec(TILE_OUTPUTS, UInt(16.W)))
  val tileFloatFinalOut = Wire(Vec(TILE_OUTPUTS, UInt(16.W)))

  for (o <- 0 until TILE_OUTPUTS) {
    for (kc <- 0 until MAX_KERNEL_SIZE) {
      val validKc = kc.U < kernelSizeReg
      val tileCol = (kc + o).U(4.W)
      val kernelElem = ((computeMacRow * kernelSizeReg) +& kc.U(3.W))(4, 0)

      val inVal  = tileBuf(computeBufIdReg)(computeMacRow)(tileCol(2, 0))
      val kerVal = kernelBuf(kernelElem)
      val prod   = fp16Mul(inVal, kerVal)

      // Invalid kernel columns contribute +0.0 half.
      tileFloatTerms(o)(kc) := Mux(validKc, prod, 0.U(16.W))
    }

    val acc1 = fp16Add(tileFloatAcc(o), tileFloatTerms(o)(0))
    val acc2 = fp16Add(acc1, tileFloatTerms(o)(1))
    val acc3 = fp16Add(acc2, tileFloatTerms(o)(2))
    val acc4 = fp16Add(acc3, tileFloatTerms(o)(3))
    val acc5 = fp16Add(acc4, tileFloatTerms(o)(4))

    tileFloatNextAcc(o) := Mux(
      kernelSizeReg === 1.U,
      acc1,
      Mux(kernelSizeReg === 3.U, acc3, acc5)
    )

    val withBias = fp16Add(tileFloatNextAcc(o), biasBuf)
    tileFloatFinalOut(o) := Mux(biasEnableReg, withBias, tileFloatNextAcc(o))
  }

  val selectedFinalOut = Wire(Vec(TILE_OUTPUTS, UInt(16.W)))
  for (i <- 0 until TILE_OUTPUTS) {
    selectedFinalOut(i) := Mux(dataTypeReg === DATA_FLOAT16, tileFloatFinalOut(i), fixedFinalOut(i))
  }

  val computeOutBaseIdx = (computeBaseRowReg << 5) + computeBaseColReg
  val computeStoreAddr  = outputAddrReg + (computeOutBaseIdx << 1)
  val computeStoreData  = Cat(selectedFinalOut(3), selectedFinalOut(2), selectedFinalOut(1), selectedFinalOut(0))

  def resetTileLoadCursors(startRow: UInt): Unit = {
    tileLoadRow := startRow
    tileLoadCol := 0.U
    tileLoadCnt := 0.U
  }

  def resetTileComputeCursors(): Unit = {
    computeMacRow := 0.U
  }

  def resetTileAccumulators(): Unit = {
    for (i <- 0 until TILE_OUTPUTS) {
      tileAcc(i) := 0.S
      tileFloatAcc(i) := 0.U
      tileOutReg(i) := 0.U
    }
  }

  def advanceTileGenerator(): Unit = {
    when(tileGenRowReg === (INPUT_SIZE - 1).U) {
      tileGenRowReg := 0.U
      tileGenColReg := tileGenColReg + TILE_OUTPUTS.U
    }.otherwise {
      tileGenRowReg := tileGenRowReg + 1.U
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

  def advanceTileLoadCursorByN(step: UInt): Unit = {
    tileLoadCnt := tileLoadCnt + step

    val nextCol = tileLoadCol +& step
    when(nextCol >= tileWidth) {
      tileLoadCol := 0.U
      tileLoadRow := tileLoadRow + 1.U
    }.otherwise {
      tileLoadCol := nextCol(3, 0)
    }
  }

  def clearPipelineState(): Unit = {
    loadState := loadIdle
    computeState := computeIdle

    tileGenRowReg := 0.U
    tileGenColReg := 0.U

    tilesIssuedCnt := 0.U
    tilesLoadedCnt := 0.U
    tilesComputedCnt := 0.U
    tilesStoredCnt := 0.U

    for (b <- 0 until 2) {
      tileBufState(b) := bufFree
    }

    prevTileValidReg := false.B
    prevTileRowReg := 0.U
    prevTileColReg := 0.U
    prevTileBufIdReg := 0.U
    loadReuseActiveReg := false.B

    resetTileLoadCursors(0.U)
    resetTileComputeCursors()
    resetTileAccumulators()

    memInflight := false.B
    memKindReg := memNone
    memPreferStore := false.B
  }

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
        when(configuredReg && kernelConfiguredReg && loadedReg) {
          clearPipelineState()
          computedReg := false.B
          state := sCompute
        }.otherwise {
          resultReg := RET_ERROR
          state := sRespond
        }

      }.elsewhen(doStore) {
        when(computedReg) {
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
      val totalTiles = TOTAL_TILES.U(9.W)
      val allTileLoadsStarted = tilesIssuedCnt === totalTiles

      val anyFreeBuf = (tileBufState(0) === bufFree) || (tileBufState(1) === bufFree)
      val defaultFreeBufId = Mux(tileBufState(0) === bufFree, 0.U(1.W), 1.U(1.W))

      val reuseCandidate =
        prevTileValidReg &&
        (tileGenRowReg =/= 0.U) &&
        (tileGenColReg === prevTileColReg) &&
        (tileGenRowReg === (prevTileRowReg + 1.U))

      val reuseDestBufId = prevTileBufIdReg ^ 1.U(1.W)
      val canReuseNow = reuseCandidate && (tileBufState(reuseDestBufId) === bufFree)

      val hasFreeBuf = anyFreeBuf
      val freeBufId = Mux(canReuseNow, reuseDestBufId, defaultFreeBufId)

      // -----------------------------
      // Load engine.
      // -----------------------------
      switch(loadState) {
        is(loadIdle) {
          when(!allTileLoadsStarted && hasFreeBuf) {
            loadBaseRowReg := tileGenRowReg
            loadBaseColReg := tileGenColReg
            loadBufIdReg := freeBufId
            tileBufState(freeBufId) := bufLoading
            loadReuseActiveReg := canReuseNow

            // Vertical row reuse:
            // new row 0 gets old row 1, ..., new row K-2 gets old row K-1.
            // Then the load engine only loads the newest bottom row K-1.
            when(canReuseNow) {
              for (r <- 0 until (MAX_KERNEL_SIZE - 1)) {
                for (c <- 0 until MAX_TILE_WIDTH) {
                  when((r.U < (kernelSizeReg - 1.U)) && (c.U < tileWidth)) {
                    tileBuf(freeBufId)(r)(c) := tileBuf(prevTileBufIdReg)(r + 1)(c)
                  }
                }
              }
              resetTileLoadCursors(kernelSizeReg - 1.U)
            }.otherwise {
              resetTileLoadCursors(0.U)
            }

            tilesIssuedCnt := tilesIssuedCnt + 1.U
            advanceTileGenerator()
            loadState := loadRun
          }
        }

        is(loadRun) {
          when(tileLoadDone) {
            loadState := loadEnq
          }.elsewhen(!tileLoadInputValid) {
            tileBuf(loadBufIdReg)(tileLoadRow)(tileLoadCol(2, 0)) := 0.U
            advanceTileLoadCursor()
          }
        }

        is(loadEnq) {
          loadToComputeQ.io.enq.valid := true.B
          loadToComputeQ.io.enq.bits.row := loadBaseRowReg
          loadToComputeQ.io.enq.bits.col := loadBaseColReg
          loadToComputeQ.io.enq.bits.bufId := loadBufIdReg

          when(loadToComputeQ.io.enq.fire) {
            tileBufState(loadBufIdReg) := bufReady
            prevTileValidReg := true.B
            prevTileRowReg := loadBaseRowReg
            prevTileColReg := loadBaseColReg
            prevTileBufIdReg := loadBufIdReg
            loadReuseActiveReg := false.B
            tilesLoadedCnt := tilesLoadedCnt + 1.U
            loadState := loadIdle
          }
        }
      }

      // -----------------------------
      // Compute engine.
      // -----------------------------
      when(computeState === computeIdle) {
        loadToComputeQ.io.deq.ready := true.B

        when(loadToComputeQ.io.deq.fire) {
          computeBaseRowReg := loadToComputeQ.io.deq.bits.row
          computeBaseColReg := loadToComputeQ.io.deq.bits.col
          computeBufIdReg := loadToComputeQ.io.deq.bits.bufId
          tileBufState(loadToComputeQ.io.deq.bits.bufId) := bufComputing
          resetTileComputeCursors()
          resetTileAccumulators()
          computeState := computeRun
        }
      }.otherwise {
        val isFinalMacRow = computeMacRow === (kernelSizeReg - 1.U)

        when(isFinalMacRow) {
          outputQ.io.enq.valid := true.B
          outputQ.io.enq.bits.addr := computeStoreAddr
          outputQ.io.enq.bits.data := computeStoreData

          when(outputQ.io.enq.fire) {
            for (i <- 0 until TILE_OUTPUTS) {
              tileOutReg(i) := selectedFinalOut(i)
            }

            tileBufState(computeBufIdReg) := bufFree
            tilesComputedCnt := tilesComputedCnt + 1.U
            computeState := computeIdle
          }
        }.otherwise {
          when(dataTypeReg === DATA_FLOAT16) {
            for (i <- 0 until TILE_OUTPUTS) {
              tileFloatAcc(i) := tileFloatNextAcc(i)
            }
          }.otherwise {
            for (i <- 0 until TILE_OUTPUTS) {
              tileAcc(i) := tileNextAcc(i)
            }
          }

          computeMacRow := computeMacRow + 1.U
        }
      }

      // -----------------------------
      // Store engine and memory scheduler.
      // Only one RoCC memory request can be issued at a time here. This still
      // allows compute to overlap with either load or store memory latency.
      // -----------------------------
      val computeStarving = (computeState === computeIdle) && !loadToComputeQ.io.deq.valid
      val loadNeedsMemory =
        (loadState === loadRun) &&
        !tileLoadDone &&
        tileLoadInputValid &&
        !memInflight

      val storeNeedsMemory = outputQ.io.deq.valid && !memInflight
      val chooseLoad = loadNeedsMemory && (!storeNeedsMemory || computeStarving || !memPreferStore)
      val chooseStore = storeNeedsMemory && !chooseLoad

      when(chooseLoad) {
        io.mem.req.valid := true.B
        io.mem.req.bits.addr := tileLoadMemAddr
        io.mem.req.bits.tag := loadBufIdReg
        io.mem.req.bits.cmd := M_XRD
        io.mem.req.bits.size := Mux(tileLoadCanWide, 3.U, 1.U)
        io.mem.req.bits.signed := true.B
        io.mem.req.bits.dprv := dprvReg

        when(io.mem.req.fire) {
          memInflight := true.B
          memKindReg := memLoadTile
          memLoadBufIdReg := loadBufIdReg
          memLoadRowReg := tileLoadRow
          memLoadColReg := tileLoadCol
          memLoadWideReg := tileLoadCanWide
          memLoadWideCountReg := Mux(tileLoadCanWide, tileLoadWideCount, 0.U(3.W))
          memPreferStore := true.B
        }
      }.elsewhen(chooseStore) {
        io.mem.req.valid := true.B
        io.mem.req.bits.addr := outputQ.io.deq.bits.addr
        io.mem.req.bits.tag := 2.U
        io.mem.req.bits.cmd := M_XWR
        io.mem.req.bits.size := 3.U
        io.mem.req.bits.signed := false.B
        io.mem.req.bits.data := outputQ.io.deq.bits.data
        io.mem.req.bits.dprv := dprvReg

        outputQ.io.deq.ready := io.mem.req.ready

        when(io.mem.req.fire) {
          memInflight := true.B
          memKindReg := memStoreOut
          memPreferStore := false.B
        }
      }

      // Memory response demux for the single outstanding request.
      when(memInflight && io.mem.resp.valid) {
        when(memKindReg === memLoadTile) {
          when(memLoadWideReg) {
            tileBuf(memLoadBufIdReg)(memLoadRowReg)(memLoadColReg(2, 0)) := io.mem.resp.bits.data(15, 0)

            when(memLoadWideCountReg >= 2.U) {
              tileBuf(memLoadBufIdReg)(memLoadRowReg)((memLoadColReg + 1.U)(2, 0)) := io.mem.resp.bits.data(31, 16)
            }

            when(memLoadWideCountReg >= 3.U) {
              tileBuf(memLoadBufIdReg)(memLoadRowReg)((memLoadColReg + 2.U)(2, 0)) := io.mem.resp.bits.data(47, 32)
            }

            when(memLoadWideCountReg >= 4.U) {
              tileBuf(memLoadBufIdReg)(memLoadRowReg)((memLoadColReg + 3.U)(2, 0)) := io.mem.resp.bits.data(63, 48)
            }

            advanceTileLoadCursorByN(memLoadWideCountReg)
          }.otherwise {
            tileBuf(memLoadBufIdReg)(memLoadRowReg)(memLoadColReg(2, 0)) := io.mem.resp.bits.data(15, 0)
            advanceTileLoadCursor()
          }
        }.elsewhen(memKindReg === memStoreOut) {
          tilesStoredCnt := tilesStoredCnt + 1.U
        }

        memInflight := false.B
        memKindReg := memNone
      }

      when(tilesStoredCnt === totalTiles && !memInflight) {
        computedReg := true.B
        resultReg := RET_SUCCESS
        state := sRespond
      }
    }

    // Store is now a compatibility state. The pipelined sCompute state has
    // already written all output tiles to memory before it returns success.
    is(sStore) {
      resultReg := RET_SUCCESS
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
