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

  // English comment: Buffer incoming commands
  val cmd = Queue(io.cmd, 1)

  // English comment: Fixed design parameters
  val INPUT_SIZE  = 32
  val KERNEL_SIZE = 3
  val INPUT_ELEMS = INPUT_SIZE * INPUT_SIZE
  val KERNEL_ELEMS = KERNEL_SIZE * KERNEL_SIZE

  // English comment: funct7 encoding
  val FUNCT_LOAD    = 0.U(7.W)
  val FUNCT_COMPUTE = 1.U(7.W)
  val FUNCT_STORE   = 2.U(7.W)

  // English comment: FSM states
  val sIdle :: sDecode :: sLoad :: sCompute :: sStore :: sRespond :: Nil = Enum(6)
  val state = RegInit(sIdle)

  // English comment: Latched command fields
  val functReg = RegInit(0.U(7.W))
  val rs1Reg   = RegInit(0.U(xLen.W))
  val rs2Reg   = RegInit(0.U(xLen.W))
  val rdReg    = RegInit(0.U(5.W))

  // English comment: Address registers
  val inputAddrReg  = RegInit(0.U(xLen.W))
  val kernelAddrReg = RegInit(0.U(xLen.W))
  val outputAddrReg = RegInit(0.U(xLen.W))

  // English comment: Status/result registers
  val resultReg     = RegInit(0.U(xLen.W))
  val illegalCmdReg = RegInit(false.B)

  // English comment: Internal accelerator buffers
  val inputBuf  = Reg(Vec(INPUT_ELEMS, SInt(16.W)))
  val kernelBuf = Reg(Vec(KERNEL_ELEMS, SInt(16.W)))
  val outputBuf = Reg(Vec(INPUT_ELEMS, SInt(16.W)))

  // English comment: Load/store bookkeeping
  val inputLoadIdx   = RegInit(0.U(10.W))
  val kernelLoadIdx  = RegInit(0.U(4.W))
  val storeIdx       = RegInit(0.U(10.W))
  val memInflight    = RegInit(false.B)
  val issuedIsInput  = RegInit(false.B)
  val issuedIndex    = RegInit(0.U(10.W))

  // English comment: Compute bookkeeping
  val outRow = RegInit(0.U(6.W))
  val outCol = RegInit(0.U(6.W))

  // English comment: Helper wires
  val doLoad    = functReg === FUNCT_LOAD
  val doCompute = functReg === FUNCT_COMPUTE
  val doStore   = functReg === FUNCT_STORE

  // English comment: Default command handshake
  cmd.ready := false.B

  // English comment: Default response interface
  io.resp.valid := false.B
  io.resp.bits.rd := rdReg
  io.resp.bits.data := resultReg

  io.busy := (state =/= sIdle)
  io.interrupt := false.B

  // English comment: Default memory request interface
  io.mem.req.valid := false.B
  io.mem.req.bits.addr := 0.U
  io.mem.req.bits.tag := 0.U
  io.mem.req.bits.cmd := M_XRD
  io.mem.req.bits.typ := MT_H
  io.mem.req.bits.phys := false.B
  io.mem.req.bits.signed := true.B
  io.mem.req.bits.data := 0.U
  io.mem.req.bits.dprv := cmd.bits.status.dprv

  // English comment: Debug print for accepted command
  when(cmd.fire) {
    printf("[MyConvAccel] CMD fire funct=%d rs1=%x rs2=%x rd=%d state=%d\n",
      cmd.bits.inst.funct, cmd.bits.rs1, cmd.bits.rs2, cmd.bits.inst.rd, state)
  }

  // English comment: Helper function for padded 3x3 access
  def getInputAt(baseRow: UInt, baseCol: UInt, kr: Int, kc: Int): SInt = {
    val rowValid = kr match {
      case 0 => baseRow =/= 0.U
      case 1 => true.B
      case 2 => baseRow =/= (INPUT_SIZE - 1).U
    }

    val colValid = kc match {
      case 0 => baseCol =/= 0.U
      case 1 => true.B
      case 2 => baseCol =/= (INPUT_SIZE - 1).U
    }

    val rowIdx = Wire(UInt(6.W))
    val colIdx = Wire(UInt(6.W))

    rowIdx := baseRow
    colIdx := baseCol

    if (kr == 0) rowIdx := baseRow - 1.U
    if (kr == 2) rowIdx := baseRow + 1.U

    if (kc == 0) colIdx := baseCol - 1.U
    if (kc == 2) colIdx := baseCol + 1.U

    Mux(rowValid && colValid, inputBuf(rowIdx * INPUT_SIZE.U + colIdx), 0.S(16.W))
  }

  // English comment: Combinational MAC tree for one output element
  val macTerms = Wire(Vec(KERNEL_ELEMS, SInt(32.W)))
  for (kr <- 0 until KERNEL_SIZE) {
    for (kc <- 0 until KERNEL_SIZE) {
      val inVal = getInputAt(outRow, outCol, kr, kc)
      val kerVal = kernelBuf(kr * KERNEL_SIZE + kc)
      // English comment: 8.8 x 8.8 -> 16.16, then shift back to 8.8
      macTerms(kr * KERNEL_SIZE + kc) := (inVal * kerVal) >> 8
    }
  }
  val macSum = macTerms.reduce(_ + _)
  val macOut16 = macSum.asUInt()(15, 0).asSInt

  switch(state) {

    is(sIdle) {
      illegalCmdReg := false.B
      cmd.ready := true.B

      when(cmd.fire) {
        // English comment: Latch command fields
        functReg := cmd.bits.inst.funct
        rs1Reg   := cmd.bits.rs1
        rs2Reg   := cmd.bits.rs2
        rdReg    := cmd.bits.inst.rd
        state    := sDecode
      }
    }

    is(sDecode) {
      when(doLoad) {
        // English comment: LOAD uses rs1=input address and rs2=kernel address
        inputAddrReg := rs1Reg
        kernelAddrReg := rs2Reg

        inputLoadIdx  := 0.U
        kernelLoadIdx := 0.U
        memInflight   := false.B

        printf("[MyConvAccel] sDecode -> sLoad inputAddr=%x kernelAddr=%x\n", rs1Reg, rs2Reg)
        state := sLoad

      }.elsewhen(doCompute) {
        // English comment: COMPUTE uses previously loaded buffers
        outRow := 0.U
        outCol := 0.U

        printf("[MyConvAccel] sDecode -> sCompute\n")
        state := sCompute

      }.elsewhen(doStore) {
        // English comment: STORE uses rs1=output address
        outputAddrReg := rs1Reg
        storeIdx := 0.U

        printf("[MyConvAccel] sDecode -> sStore outputAddr=%x\n", rs1Reg)
        state := sStore

      }.otherwise {
        illegalCmdReg := true.B
        resultReg := "hdead".U
        printf("[MyConvAccel] Illegal funct=%d\n", functReg)
        state := sRespond
      }
    }

    is(sLoad) {
      // English comment: Issue one read at a time for input matrix then kernel
      when(!memInflight) {
        when(inputLoadIdx < INPUT_ELEMS.U) {
          io.mem.req.valid := true.B
          io.mem.req.bits.addr := inputAddrReg + (inputLoadIdx << 1)
          io.mem.req.bits.tag := 0.U
          io.mem.req.bits.cmd := M_XRD
          io.mem.req.bits.typ := MT_H
          io.mem.req.bits.signed := true.B

          when(io.mem.req.fire) {
            memInflight := true.B
            issuedIsInput := true.B
            issuedIndex := inputLoadIdx
            printf("[MyConvAccel] LOAD input idx=%d addr=%x\n", inputLoadIdx, inputAddrReg + (inputLoadIdx << 1))
          }

        }.elsewhen(kernelLoadIdx < KERNEL_ELEMS.U) {
          io.mem.req.valid := true.B
          io.mem.req.bits.addr := kernelAddrReg + (kernelLoadIdx << 1)
          io.mem.req.bits.tag := 1.U
          io.mem.req.bits.cmd := M_XRD
          io.mem.req.bits.typ := MT_H
          io.mem.req.bits.signed := true.B

          when(io.mem.req.fire) {
            memInflight := true.B
            issuedIsInput := false.B
            issuedIndex := kernelLoadIdx
            printf("[MyConvAccel] LOAD kernel idx=%d addr=%x\n", kernelLoadIdx, kernelAddrReg + (kernelLoadIdx << 1))
          }

        }.otherwise {
          resultReg := 1.U
          printf("[MyConvAccel] sLoad -> sRespond done\n")
          state := sRespond
        }
      }

      when(io.mem.resp.valid) {
        val loadedData = io.mem.resp.bits.data(15, 0).asSInt

        when(issuedIsInput) {
          inputBuf(issuedIndex) := loadedData
          inputLoadIdx := inputLoadIdx + 1.U
          printf("[MyConvAccel] LOAD RESP input idx=%d data=%x\n", issuedIndex, io.mem.resp.bits.data(15, 0))
        }.otherwise {
          kernelBuf(issuedIndex(3, 0)) := loadedData
          kernelLoadIdx := kernelLoadIdx + 1.U
          printf("[MyConvAccel] LOAD RESP kernel idx=%d data=%x\n", issuedIndex, io.mem.resp.bits.data(15, 0))
        }

        memInflight := false.B
      }
    }

    is(sCompute) {
      // English comment: Compute one output element per cycle
      outputBuf(outRow * INPUT_SIZE.U + outCol) := macOut16

      printf("[MyConvAccel] COMPUTE row=%d col=%d out=%x\n",
        outRow, outCol, macOut16.asUInt)

      when(outCol === (INPUT_SIZE - 1).U) {
        outCol := 0.U
        when(outRow === (INPUT_SIZE - 1).U) {
          outRow := 0.U
          resultReg := 1.U
          printf("[MyConvAccel] sCompute -> sRespond done\n")
          state := sRespond
        }.otherwise {
          outRow := outRow + 1.U
        }
      }.otherwise {
        outCol := outCol + 1.U
      }
    }

    is(sStore) {
      // English comment: Issue one store per cycle
      when(storeIdx < INPUT_ELEMS.U) {
        io.mem.req.valid := true.B
        io.mem.req.bits.addr := outputAddrReg + (storeIdx << 1)
        io.mem.req.bits.tag := 2.U
        io.mem.req.bits.cmd := M_XWR
        io.mem.req.bits.typ := MT_H
        io.mem.req.bits.signed := true.B
        io.mem.req.bits.data := outputBuf(storeIdx).pad(xLen).asUInt

        when(io.mem.req.fire) {
          printf("[MyConvAccel] STORE idx=%d addr=%x data=%x\n",
            storeIdx, outputAddrReg + (storeIdx << 1), outputBuf(storeIdx).asUInt)
          storeIdx := storeIdx + 1.U
        }
      }.otherwise {
        resultReg := 1.U
        printf("[MyConvAccel] sStore -> sRespond done\n")
        state := sRespond
      }
    }

    is(sRespond) {
      io.resp.valid := true.B

      when(io.resp.fire) {
        printf("[MyConvAccel] RESP fire rd=%d data=%x illegal=%d\n",
          rdReg, resultReg, illegalCmdReg)
        state := sIdle
      }
    }
  }
}
