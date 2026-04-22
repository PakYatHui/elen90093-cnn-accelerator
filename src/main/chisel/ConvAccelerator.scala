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

  // English comment: FSM states
  val sIdle :: sDecode :: sLoad :: sCompute :: sStore :: sRespond :: Nil = Enum(6)
  val state = RegInit(sIdle)

  // English comment: Command encoding from funct field
  val funct = cmd.bits.inst.funct
  val doLoad    = funct === 0.U
  val doCompute = funct === 1.U
  val doStore   = funct === 2.U

  // English comment: Internal registers for command information
  val functReg = RegInit(0.U(7.W))
  val rs1Reg   = RegInit(0.U(xLen.W))
  val rs2Reg   = RegInit(0.U(xLen.W))
  val rdReg    = RegInit(0.U(5.W))

  // English comment: Internal registers for accelerator data/config
  val inputAddrReg  = RegInit(0.U(xLen.W))
  val kernelAddrReg = RegInit(0.U(xLen.W))
  val outputAddrReg = RegInit(0.U(xLen.W))
  val resultReg     = RegInit(0.U(xLen.W))

  // English comment: Internal control flags
  val illegalCmdReg = RegInit(false.B)

  // English comment: Simple counters used as placeholders for multi-cycle operations
  val loadCounter    = RegInit(0.U(8.W))
  val computeCounter = RegInit(0.U(8.W))
  val storeCounter   = RegInit(0.U(8.W))

  // English comment: Default handshake settings
  cmd.ready := false.B

  io.resp.valid := false.B
  io.resp.bits.rd := rdReg
  io.resp.bits.data := resultReg

  io.busy := (state =/= sIdle)
  io.interrupt := false.B

  // English comment: Default memory interface values
  io.mem.req.valid := false.B
  io.mem.req.bits := DontCare

  // English comment: Debug prints for command fire
  when(cmd.fire) {
    printf("[MyConvAccel] CMD fire funct=%d rs1=%x rs2=%x rd=%d state=%d\n",
      funct, cmd.bits.rs1, cmd.bits.rs2, cmd.bits.inst.rd, state)
  }

  switch(state) {

    is(sIdle) {
      illegalCmdReg := false.B
      cmd.ready := true.B

      when(cmd.fire) {
        // English comment: Latch command fields before decode
        functReg := cmd.bits.inst.funct
        rs1Reg   := cmd.bits.rs1
        rs2Reg   := cmd.bits.rs2
        rdReg    := cmd.bits.inst.rd

        printf("[MyConvAccel] sIdle -> sDecode, latch funct=%d rs1=%x rs2=%x rd=%d\n",
          cmd.bits.inst.funct, cmd.bits.rs1, cmd.bits.rs2, cmd.bits.inst.rd)

        state := sDecode
      }
    }

    is(sDecode) {
      // English comment: Decode the latched command
      when(functReg === 0.U) {
        printf("[MyConvAccel] sDecode -> sLoad\n")
        state := sLoad
      }.elsewhen(functReg === 1.U) {
        printf("[MyConvAccel] sDecode -> sCompute\n")
        state := sCompute
      }.elsewhen(functReg === 2.U) {
        printf("[MyConvAccel] sDecode -> sStore\n")
        state := sStore
      }.otherwise {
        printf("[MyConvAccel] sDecode -> sIdle, illegal command funct=%d\n", functReg)
        illegalCmdReg := true.B
        state := sIdle
      }
    }

    is(sLoad) {
      // English comment: Placeholder load behavior
      // English comment: rs1/rs2 can be used as addresses or configuration values
      inputAddrReg := rs1Reg
      kernelAddrReg := rs2Reg

      loadCounter := loadCounter + 1.U

      printf("[MyConvAccel] sLoad, inputAddr=%x kernelAddr=%x counter=%d\n",
        rs1Reg, rs2Reg, loadCounter)

      // English comment: Replace this with real memory-read completion later
      when(loadCounter === 3.U) {
        loadCounter := 0.U
        printf("[MyConvAccel] sLoad -> sRespond, load done\n")
        state := sRespond
      }
    }

    is(sCompute) {
      computeCounter := computeCounter + 1.U

      printf("[MyConvAccel] sCompute, counter=%d\n", computeCounter)

      // English comment: Placeholder compute result
      // English comment: Replace this with real MAC/convolution datapath output later
      when(computeCounter === 5.U) {
        computeCounter := 0.U
        resultReg := rs1Reg + rs2Reg
        printf("[MyConvAccel] sCompute -> sRespond, placeholder result=%x\n", rs1Reg + rs2Reg)
        state := sRespond
      }
    }

    is(sStore) {
      // English comment: Placeholder store behavior
      // English comment: rs1 can be treated as output address for now
      outputAddrReg := rs1Reg
      storeCounter := storeCounter + 1.U

      printf("[MyConvAccel] sStore, outputAddr=%x data=%x counter=%d\n",
        rs1Reg, resultReg, storeCounter)

      // English comment: Replace this with real memory-write completion later
      when(storeCounter === 3.U) {
        storeCounter := 0.U
        printf("[MyConvAccel] sStore -> sRespond, store done\n")
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
