package myaccelerators

import chisel3._
import chisel3.util._

class ConvAccelIO extends Bundle {
  val cmd_valid     = Input(Bool())
  val funct7        = Input(UInt(7.W))
  val rs1           = Input(UInt(64.W))
  val rs2           = Input(UInt(64.W))

  val busy          = Output(Bool())
  val done          = Output(Bool())
  val resp_valid    = Output(Bool())

  val mem_read_en   = Output(Bool())
  val mem_write_en  = Output(Bool())
  val compute_en    = Output(Bool())

  // 你之后可以继续扩展
  val illegal_cmd   = Output(Bool())
  val result        = Output(UInt(64.W))
}

class ConvAccelerator extends Module {
  val io = IO(new ConvAccelIO)

  // -------------------------
  // funct7 definitions
  // -------------------------
  val FUNCT_LOAD    = "b0000001".U(7.W)
  val FUNCT_COMPUTE = "b0000010".U(7.W)
  val FUNCT_STORE   = "b0000011".U(7.W)

  // -------------------------
  // FSM state definitions
  // -------------------------
  val sIdle :: sDecode :: sLoad :: sCompute :: sStore :: sRespond :: Nil = Enum(6)
  val state = RegInit(sIdle)

  // -------------------------
  // internal registers
  // -------------------------
  val funct7Reg     = RegInit(0.U(7.W))
  val rs1Reg        = RegInit(0.U(64.W))
  val rs2Reg        = RegInit(0.U(64.W))
  val resultReg     = RegInit(0.U(64.W))

  // 假设 done signal，后面你可以接真正 datapath / memory handshake
  val loadDone      = WireDefault(false.B)
  val computeDone   = WireDefault(false.B)
  val storeDone     = WireDefault(false.B)

  // -------------------------
  // default outputs
  // -------------------------
  io.busy         := false.B
  io.done         := false.B
  io.resp_valid   := false.B
  io.mem_read_en  := false.B
  io.mem_write_en := false.B
  io.compute_en   := false.B
  io.illegal_cmd  := false.B
  io.result       := resultReg

  // -------------------------
  // temporary simple-done model
  // 你后面要换成真正的 handshake / counter / datapath done
  // -------------------------
  val loadCounter    = RegInit(0.U(4.W))
  val computeCounter = RegInit(0.U(4.W))
  val storeCounter   = RegInit(0.U(4.W))

  when (state =/= sLoad)    { loadCounter    := 0.U }
  when (state =/= sCompute) { computeCounter := 0.U }
  when (state =/= sStore)   { storeCounter   := 0.U }

  val loadDoneReg    = RegInit(false.B)
  val computeDoneReg = RegInit(false.B)
  val storeDoneReg   = RegInit(false.B)

  loadDoneReg    := false.B
  computeDoneReg := false.B
  storeDoneReg   := false.B

  // -------------------------
  // FSM
  // -------------------------
  switch(state) {

    is(sIdle) {
      io.busy := false.B
      io.done := false.B

      when(io.cmd_valid) {
        funct7Reg := io.funct7
        rs1Reg    := io.rs1
        rs2Reg    := io.rs2
        state     := sDecode
      }
    }

    is(sDecode) {
      io.busy := true.B

      when(funct7Reg === FUNCT_LOAD) {
        state := sLoad
      }.elsewhen(funct7Reg === FUNCT_COMPUTE) {
        state := sCompute
      }.elsewhen(funct7Reg === FUNCT_STORE) {
        state := sStore
      }.otherwise {
        io.illegal_cmd := true.B
        state := sIdle
      }
    }

    is(sLoad) {
      io.busy        := true.B
      io.mem_read_en := true.B

      // 这里先用 counter 模拟 load 完成
      loadCounter := loadCounter + 1.U
      when(loadCounter === 3.U) {
        loadDoneReg := true.B
      }

      when(loadDoneReg) {
        // TODO:
        // 这里后面接真正 memory read 回来的数据
        // 例如 input buffer / kernel buffer / config registers
        state := sRespond
      }
    }

    is(sCompute) {
      io.busy       := true.B
      io.compute_en := true.B

      // 这里先用 counter 模拟 compute 完成
      computeCounter := computeCounter + 1.U
      when(computeCounter === 5.U) {
        computeDoneReg := true.B
      }

      when(computeDoneReg) {
        // TODO:
        // 这里后面接真正的 MAC datapath 输出
        // 现在先做一个 placeholder
        resultReg := rs1Reg + rs2Reg
        state := sRespond
      }
    }

    is(sStore) {
      io.busy         := true.B
      io.mem_write_en := true.B

      // 这里先用 counter 模拟 store 完成
      storeCounter := storeCounter + 1.U
      when(storeCounter === 3.U) {
        storeDoneReg := true.B
      }

      when(storeDoneReg) {
        // TODO:
        // 这里后面接真正 memory write handshake
        state := sRespond
      }
    }

    is(sRespond) {
      io.busy       := false.B
      io.done       := true.B
      io.resp_valid := true.B

      // 一拍 response 后回到 Idle
      state := sIdle
    }
  }
}
