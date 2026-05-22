package myaccelerators

import chisel3._
import chisel3.util._

import org.chipsalliance.cde.config._
import org.chipsalliance.diplomacy.lazymodule._
import freechips.rocketchip.tile._
import freechips.rocketchip.rocket._

// =============================================================================
// Phase 5B fixed16 partial-tail wide-load accelerator.
// Interface remains CONFIG -> KERNEL -> DATA -> COMPUTE -> STORE.
//
// Based on the Phase 4B PASS checkpoint:
//   - 2D shared tileBuf is retained.
//   - Vertical sliding reuse is retained.
//   - FSM-branch style STORE path is retained.
//
// New in Phase 5B:
//   - Opportunistic 64-bit input loads for tile rows.
//   - A wide load is used only when four consecutive int16 input values are
//     all inside the image and the byte address is 8-byte aligned.
//   - Padding edges and unaligned positions fall back to the scalar 16-bit path.
//   - No float16 and no software interface change.
// =============================================================================

class MyConvAccel(opcodes: OpcodeSet)(implicit p: Parameters) extends LazyRoCC(opcodes) {
  println("DEBUG: Elaborating MyConvAccel Phase 5B partial-tail wide input load version")
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
  val MAX_TILE_ELEMS   = MAX_KERNEL_SIZE * MAX_TILE_WIDTH

  val RET_ERROR   = 0.U(xLen.W)
  val RET_SUCCESS = 1.U(xLen.W)

  val DATA_FIXED16 = 0.U(2.W)
  val DATA_FLOAT16 = 1.U(2.W)

  val FUNCT_CONFIG  = 0.U(7.W)
  val FUNCT_DATA    = 1.U(7.W)
  val FUNCT_COMPUTE = 2.U(7.W)
  val FUNCT_STORE   = 3.U(7.W)
  val FUNCT_KERNEL  = 4.U(7.W)

  val PRELOAD_KERNEL = 0.U(1.W)
  val PRELOAD_BIAS   = 1.U(1.W)

  val sIdle :: sDecode :: sConfig :: sKernel :: sLoad :: sCompute :: sStore :: sRespond :: Nil = Enum(8)
  val state = RegInit(sIdle)

  val ftIdle :: ftReuseRows :: ftLoadTile :: ftLoadWait :: ftCompute :: ftStore :: ftStoreWait :: ftDone :: Nil = Enum(8)
  val fixedTileState = RegInit(ftIdle)

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
  val outputBuf = Reg(Vec(INPUT_ELEMS, UInt(16.W)))
  val biasBuf   = RegInit(0.U(16.W))

  val tileBuf = Reg(Vec(MAX_KERNEL_SIZE, Vec(MAX_TILE_WIDTH, UInt(16.W))))

  val preloadIdx      = RegInit(0.U(6.W))
  val preloadInflight = RegInit(false.B)
  val preloadType     = RegInit(PRELOAD_KERNEL)
  val biasLoadedReg   = RegInit(true.B)

  // Tile traversal is column-block-major for vertical reuse:
  //   col block 0: rows 0..31, then col block 4: rows 0..31, etc.
  val tileBaseRowReg = RegInit(0.U(6.W))
  val tileBaseColReg = RegInit(0.U(6.W))

  val tileLoadRow = RegInit(0.U(3.W))
  val tileLoadCol = RegInit(0.U(4.W))
  val tileLoadCnt = RegInit(0.U(6.W))

  val tileReuseActiveReg = RegInit(false.B)

  // Tracks whether the outstanding input tile load was a 64-bit load.
  // Scalar loads write one tileBuf element; wide loads write up to four adjacent elements.
  val tileLoadWideReg = RegInit(false.B)

  // Number of useful halfwords returned by the outstanding wide load.
  // This can be less than four for tail columns at the end of a tile row.
  val tileLoadWideCountReg = RegInit(0.U(3.W))

  val tileMacRow = RegInit(0.U(3.W))

  val tileAcc    = RegInit(VecInit(Seq.fill(TILE_OUTPUTS)(0.S(40.W))))
  val tileOutReg = RegInit(VecInit(Seq.fill(TILE_OUTPUTS)(0.U(16.W))))

  val storeIdx      = RegInit(0.U(11.W))
  val storeInflight = RegInit(false.B)

  val doConfig  = functReg === FUNCT_CONFIG
  val doData    = functReg === FUNCT_DATA
  val doCompute = functReg === FUNCT_COMPUTE
  val doStore   = functReg === FUNCT_STORE
  val doKernel  = functReg === FUNCT_KERNEL

  val validKernelSize =
    (rs1Reg === 1.U) || (rs1Reg === 3.U) || (rs1Reg === 5.U)

  val validDataType = rs2Reg(1, 0) === DATA_FIXED16

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

  val tileRadius  = kernelSizeReg >> 1
  val tileBaseRow = tileBaseRowReg
  val tileBaseCol = tileBaseColReg

  val tileOutBaseIdx = (tileBaseRowReg << 5) + tileBaseColReg

  val tileWidth = kernelSizeReg +& (TILE_OUTPUTS - 1).U(3.W)
  val tileElems = (kernelSizeReg * tileWidth)(5, 0)

  val tileReuseStartRow = Wire(UInt(3.W))
  tileReuseStartRow := kernelSizeReg - 1.U

  val tileLoadStartRow = Mux(tileReuseActiveReg, tileReuseStartRow, 0.U(3.W))

  val tileLoadDone = Mux(
    tileReuseActiveReg,
    tileLoadRow >= kernelSizeReg,
    tileLoadCnt >= tileElems
  )

  val tileCanReuseVert = tileBaseRowReg =/= 0.U

  val tileLoadInRowS =
    tileBaseRow.zext + tileLoadRow.zext - tileRadius.zext

  val tileLoadInColS =
    tileBaseCol.zext + tileLoadCol.zext - tileRadius.zext

  val tileLoadInputValid =
    tileLoadInRowS >= 0.S && tileLoadInRowS < INPUT_SIZE.S &&
    tileLoadInColS >= 0.S && tileLoadInColS < INPUT_SIZE.S

  val tileLoadMemIndex = (tileLoadInRowS.asUInt << 5) + tileLoadInColS.asUInt
  val tileLoadMemAddr  = inputAddrReg + (tileLoadMemIndex << 1)

  // Number of runtime tile columns remaining in the current tile row.
  val tileLoadColsRemaining = tileWidth - tileLoadCol

  val tileLoadWideCount = Mux(
    tileLoadColsRemaining >= 4.U,
    4.U(3.W),
    tileLoadColsRemaining(2, 0)
  )

  // Safe wide-load condition:
  //   - current element is valid,
  //   - at least one tile column remains,
  //   - four consecutive image columns fit inside the 32-wide input row,
  //   - the starting image column is a multiple of four, so the byte address is 8-byte aligned.
  //
  // Unlike Phase 5A, this version does not require four tile columns to remain.
  // At the tail of a tile row it still performs one aligned 64-bit read but only
  // commits the useful returned halfwords to tileBuf.
  val tileLoadCanWide =
    tileLoadInputValid &&
    (tileLoadCol < tileWidth) &&
    (tileLoadWideCount =/= 0.U) &&
    (tileLoadInColS >= 0.S) &&
    (tileLoadInColS <= (INPUT_SIZE - 4).S) &&
    (tileLoadInColS.asUInt(1, 0) === 0.U)

  val biasTerm = Wire(SInt(40.W))
  biasTerm := Mux(biasEnableReg, biasBuf.asSInt, 0.S(40.W))

  val tileTerms   = Wire(Vec(TILE_OUTPUTS, Vec(MAX_KERNEL_SIZE, SInt(40.W))))
  val tileRowSum  = Wire(Vec(TILE_OUTPUTS, SInt(40.W)))
  val tileNextAcc = Wire(Vec(TILE_OUTPUTS, SInt(40.W)))

  for (o <- 0 until TILE_OUTPUTS) {
    for (kc <- 0 until MAX_KERNEL_SIZE) {
      val validKc = kc.U < kernelSizeReg
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

  def resetTileLoadCursors(startRow: UInt): Unit = {
    tileLoadRow := startRow
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
          tileBaseRowReg := 0.U
          tileBaseColReg := 0.U
          tileReuseActiveReg := false.B
          tileLoadWideReg := false.B
          tileLoadWideCountReg := 0.U(3.W)
          resetTileLoadCursors(0.U)
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
          when(tileBaseColReg >= INPUT_SIZE.U) {
            computedReg := true.B
            resultReg := RET_SUCCESS
            fixedTileState := ftDone
            state := sRespond
          }.otherwise {
            resetTileComputeCursors()
            resetTileAccumulators()

            when(tileCanReuseVert) {
              tileReuseActiveReg := true.B
              tileLoadWideReg := false.B
              tileLoadWideCountReg := 0.U(3.W)
              fixedTileState := ftReuseRows
            }.otherwise {
              tileReuseActiveReg := false.B
              tileLoadWideReg := false.B
              tileLoadWideCountReg := 0.U(3.W)
              resetTileLoadCursors(0.U)
              fixedTileState := ftLoadTile
            }
          }
        }

        is(ftReuseRows) {
          // Reuse the overlapping rows from the previous output row in the
          // same 4-column block: new row 0 gets old row 1, etc.
          for (r <- 0 until (MAX_KERNEL_SIZE - 1)) {
            for (c <- 0 until MAX_TILE_WIDTH) {
              when(r.U < (kernelSizeReg - 1.U) && c.U < tileWidth) {
                tileBuf(r)(c) := tileBuf(r + 1)(c)
              }
            }
          }

          tileLoadWideReg := false.B
          tileLoadWideCountReg := 0.U(3.W)
          resetTileLoadCursors(tileReuseStartRow)
          fixedTileState := ftLoadTile
        }

        is(ftLoadTile) {
          when(tileLoadDone) {
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
            io.mem.req.bits.size := Mux(tileLoadCanWide, 3.U, 1.U)
            io.mem.req.bits.signed := true.B
            io.mem.req.bits.dprv := dprvReg

            when(io.mem.req.fire) {
              tileLoadWideReg := tileLoadCanWide
              tileLoadWideCountReg := Mux(tileLoadCanWide, tileLoadWideCount, 0.U(3.W))
              fixedTileState := ftLoadWait
            }
          }
        }

        is(ftLoadWait) {
          when(io.mem.resp.valid) {
            when(tileLoadWideReg) {
              tileBuf(tileLoadRow)(tileLoadCol(2, 0)) := io.mem.resp.bits.data(15, 0)

              when(tileLoadWideCountReg >= 2.U) {
                tileBuf(tileLoadRow)((tileLoadCol + 1.U)(2, 0)) := io.mem.resp.bits.data(31, 16)
              }

              when(tileLoadWideCountReg >= 3.U) {
                tileBuf(tileLoadRow)((tileLoadCol + 2.U)(2, 0)) := io.mem.resp.bits.data(47, 32)
              }

              when(tileLoadWideCountReg >= 4.U) {
                tileBuf(tileLoadRow)((tileLoadCol + 3.U)(2, 0)) := io.mem.resp.bits.data(63, 48)
              }

              advanceTileLoadCursorByN(tileLoadWideCountReg)
            }.otherwise {
              tileBuf(tileLoadRow)(tileLoadCol(2, 0)) := io.mem.resp.bits.data(15, 0)
              advanceTileLoadCursor()
            }

            tileLoadWideReg := false.B
            tileLoadWideCountReg := 0.U(3.W)
            fixedTileState := ftLoadTile
          }
        }

        is(ftCompute) {
          when(tileMacRow === (kernelSizeReg - 1.U)) {
            for (i <- 0 until TILE_OUTPUTS) {
              val finalOut = (tileNextAcc(i) + biasTerm).asUInt(15, 0)
              tileOutReg(i) := finalOut
              outputBuf((tileOutBaseIdx + i.U)(9, 0)) := finalOut
            }

            when(tileBaseRowReg === (INPUT_SIZE - 1).U) {
              tileBaseRowReg := 0.U
              tileBaseColReg := tileBaseColReg + TILE_OUTPUTS.U
            }.otherwise {
              tileBaseRowReg := tileBaseRowReg + 1.U
            }

            fixedTileState := ftIdle

          }.otherwise {
            for (i <- 0 until TILE_OUTPUTS) {
              tileAcc(i) := tileNextAcc(i)
            }

            tileMacRow := tileMacRow + 1.U
          }
        }

        is(ftStore) {
          fixedTileState := ftIdle
        }

        is(ftStoreWait) {
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
