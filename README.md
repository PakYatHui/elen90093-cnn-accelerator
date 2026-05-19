# ELEN90093 CNN Convolution Accelerator

## Team Members

- PAK YAT HUI
- Hanburger

## Project Overview

This repository implements a RISC-V RoCC-based CNN convolution accelerator for 32x32 matrix convolution in Chipyard.

The `feature/streaming-sliding-pipeline` branch is the current streaming accelerator branch. It replaces the earlier full-image batch design with a window-based streaming pipeline using ping-pong buffers, FIFO queues, a RoCC memory scheduler, tag-based memory response routing, privilege propagation, scalar bias support, sliding-window reuse, and row-parallel MAC computation.

The accelerator supports:

- 32x32 input matrix
- 32x32 output matrix
- 1x1, 3x3, and 5x5 convolution kernels
- signed 16-bit fixed-point 8.8 mode
- IEEE-754 half-precision Float16 mode
- optional scalar bias
- zero padding at image boundaries
- RoCC custom instructions

---

## Version and Status

```text
Version: v0.5 - Streaming Sliding Pipeline with Row-Parallel MAC
Branch: feature/streaming-sliding-pipeline
Status: Fixed16 validated for 1x1, 3x3, and 5x5; Float16 path included and should be tested separately
```

This branch is no longer the old batch-style accelerator. The current design uses a streaming pipeline:

```text
CONFIG -> KERNEL -> DATA -> COMPUTE -> STORE
```

At a high level:

```text
DATA:
  preload kernel weights and optional scalar bias

COMPUTE:
  stream input windows
  reuse sliding-window data
  compute outputs
  pack outputs into 64-bit stores
  write results through the RoCC memory interface

STORE:
  compatibility command; most output stores are already issued during COMPUTE
```

---

## Main Design Difference from the Batch Version

The earlier accelerator used large internal buffers:

```text
inputBuf[1024]
outputBuf[1024]
kernelBuf[25]
```

That design loaded the full input image, computed the full output image, and then stored the full output image.

The streaming branch removes the large full-image input and output buffers. It uses:

```text
kernelBuf[25]
biasBuf[1]
windowBuf0[25]
windowBuf1[25]
windowReadyQ
outputQ
tagTable
```

This means the accelerator only keeps the current and next convolution windows in registers. The pipeline is designed to reduce register pressure and allow load, compute, and store stages to overlap.

---

## Current Architecture

### 1. RoCC Command Buffer

The accelerator receives commands through:

```scala
val cmd = Queue(io.cmd, 1)
```

This gives the RoCC command interface a small input buffer before the internal FSM decodes the command.

### 2. FSM States

The main FSM remains compatible with the previous branch:

```text
sIdle
sDecode
sConfig
sKernel
sLoad
sCompute
sStore
sRespond
```

The responsibility of each state has changed:

```text
sConfig:
  configure kernel size, data type, and bias enable

sKernel:
  record kernel address and bias address

sLoad:
  preload kernel weights and optional scalar bias

sCompute:
  run the streaming pipeline
  load windows
  perform sliding reuse
  compute outputs
  issue packed stores

sStore:
  return success after streaming computation has completed
```

### 3. PIPO / Ping-Pong Window Buffers

The accelerator uses two 25-element window buffers:

```text
windowBuf0[25]
windowBuf1[25]
```

The two buffers allow one window to be read by the compute stage while the other is written by the load stage.

Each buffer has a state:

```text
BUF_FREE
BUF_LOADING
BUF_READY
BUF_COMPUTING
```

The state transition is:

```text
FREE -> LOADING -> READY -> COMPUTING -> FREE
```

This prevents load/compute conflicts such as writing to a window buffer while compute is still reading it.

### 4. FIFO Queues

Two internal queues connect the pipeline stages:

```text
windowReadyQ
outputQ
```

`windowReadyQ` holds metadata for a loaded window:

```text
buffer ID
output index
row
column
```

`outputQ` holds packed output-store metadata:

```text
base output index
64-bit packed output data
```

This allows the load, compute, and store stages to progress independently when possible.

### 5. Sliding-Window Reuse

Within each output row, the accelerator reuses the previous window when moving from column `col` to column `col + 1`.

For a 5x5 kernel, instead of loading 25 input elements for every output pixel, the loader copies the reusable part of the previous window and only loads the new rightmost column.

Conceptually:

```text
old window -> new window
reuse K x (K - 1) elements
load only K new elements
```

For example:

```text
3x3: load 3 new input values per horizontal step
5x5: load 5 new input values per horizontal step
```

The first window of each row is still loaded as a full window.

### 6. Row-Parallel MAC

The older compute path accumulated one kernel element per cycle.

This branch uses row-parallel MAC. One cycle consumes one kernel row:

```text
1x1: 1 cycle per output
3x3: 3 cycles per output
5x5: 5 cycles per output
```

For fixed16, each row computes multiple 8.8 fixed-point multiply-accumulate terms in parallel.

For Float16, the row path uses HardFloat-based multiply and add logic on raw half-precision values.

### 7. 64-bit Packed Output Store

Four 16-bit output elements are packed into one 64-bit store word:

```text
output[col + 0]
output[col + 1]
output[col + 2]
output[col + 3]
```

Then the accelerator issues one 64-bit `M_XWR` request.

This reduces the number of store requests and avoids unnecessary partial-store transactions.

### 8. RoCC Memory Scheduler

RoCC exposes one memory request port:

```scala
io.mem.req
```

A cycle can issue either a read or a write, not both.

The streaming branch uses a scheduler to arbitrate between:

```text
window load read requests
output store write requests
```

The scheduler also checks that a free tag is available before issuing a new request.

### 9. Tag Table

The accelerator supports multiple inflight memory requests using a tag table:

```text
tagValid
tagType
tagBufId
tagElemIdx
tagElemCount
tagOutIdx
```

The tag table is needed because memory responses must be routed back to the correct destination.

Example:

```text
TAG_LOAD_WINDOW:
  write response data into windowBuf[bufId][elemIdx]

TAG_STORE_OUT:
  store acknowledgement; free the tag
```

### 10. Privilege Propagation

The accelerator latches the CPU data privilege level from the RoCC command:

```scala
dprvReg := cmd.bits.status.dprv
```

All RoCC memory requests use:

```scala
io.mem.req.bits.dprv := dprvReg
```

This is required so accelerator memory accesses follow the same privilege and protection rules as the CPU data access that launched the command sequence.

---

## Custom Instruction Interface

The accelerator uses `funct7` to select commands.

### CONFIG

```text
funct7 = 0
rs1 = kernel size
rs2 = config flags
rd  = 1 for success, 0 for error
```

Kernel size:

```text
1 = 1x1
3 = 3x3
5 = 5x5
```

Config flags:

```text
rs2[1:0] = data type
rs2[2]   = bias enable
```

Data type encoding:

```text
0 = DATA_FIXED16
1 = DATA_FLOAT16
```

### KERNEL

```text
funct7 = 4
rs1 = kernel weight address
rs2 = bias address
rd  = 1 for success, 0 for error
```

### DATA

```text
funct7 = 1
rs1 = input matrix address
rs2 = output matrix address
rd  = 1 for success, 0 for error
```

### COMPUTE

```text
funct7 = 2
rs1 = unused
rs2 = unused
rd  = 1 for success, 0 for error
```

### STORE

```text
funct7 = 3
rs1 = unused
rs2 = unused
rd  = 1 for success, 0 for error
```

The required command order is:

```c
conv_config(kernel_size, config_flags);
conv_kernel(kernel_addr, bias_addr);
conv_data(input_addr, output_addr);
conv_compute();
conv_store();
```

---

## Data Formats

### Fixed16

Fixed16 uses signed 16-bit 8.8 fixed-point format.

```text
real value = raw int16 / 256
```

MAC scaling:

```text
product = input * kernel
scaled  = product >> 8
sum    += scaled
output  = sum + bias
```

### Float16

Float16 stores IEEE-754 half-precision raw bits in `uint16_t` / `UInt(16.W)`.

The hardware path uses HardFloat modules for half-precision multiply and add.

---

## Bias Support

This branch implements real scalar bias support.

When bias is enabled:

```text
CONFIG rs2[2] = 1
KERNEL rs2 = bias address
```

The accelerator loads one 16-bit scalar bias value and adds it to every output pixel.

For fixed16, the bias is interpreted as 8.8 fixed-point.

For Float16, the bias is interpreted as raw half-precision bits.

---

## Software Tests

The standard test files are:

```text
tests/software/conv_test.c
tests/software/conv_float_test.c
```

The fixed16 test should validate:

```text
1x1 no bias
3x3 no bias
5x5 no bias
1x1 with bias
3x3 with bias
5x5 with bias
```

The Float16 test should validate:

```text
1x1 no bias
3x3 no bias
5x5 no bias
1x1 with bias
3x3 with bias
5x5 with bias
```

The tests compare all 1024 output elements against a CPU reference and print hardware and software cycle counts.

---

## Measured Fixed16 Performance Example

One tested fixed16 no-bias run produced:

```text
1x1:  5513 cycles
3x3:  9792 cycles
5x5: 14185 cycles
```

The 5x5 case is significantly faster than the earlier single-MAC streaming version because row-parallel MAC reduces compute work from 25 cycles per output to 5 cycles per output.

Exact cycle counts may vary with test code, simulator configuration, branch contents, and whether debug printing is enabled.

---

## Build and Run Instructions

### Copy Accelerator into Chipyard

```bash
cp ~/elen90093-cnn-accelerator/src/main/chisel/ConvAccelerator.scala \
~/chipyard/generators/myaccelerators/src/main/scala/ConvAccelerator.scala
```

### Build Simulator

```bash
cd ~/chipyard/sims/verilator
make clean CONFIG=MyConvAccelConfig
make CONFIG=MyConvAccelConfig
```

### Compile Fixed16 Test

```bash
cd ~/elen90093-cnn-accelerator/tests/software

riscv64-unknown-elf-gcc \
  -O0 \
  -fno-common \
  -fno-builtin-printf \
  -specs=htif_nano.specs \
  -c conv_test.c \
  -o conv_test.o

riscv64-unknown-elf-gcc \
  -static \
  -specs=htif_nano.specs \
  conv_test.o \
  -o conv_test.riscv
```

### Compile Float16 Test

```bash
cd ~/elen90093-cnn-accelerator/tests/software

riscv64-unknown-elf-gcc \
  -O0 \
  -fno-common \
  -fno-builtin-printf \
  -specs=htif_nano.specs \
  -c conv_float_test.c \
  -o conv_float_test.o

riscv64-unknown-elf-gcc \
  -static \
  -specs=htif_nano.specs \
  conv_float_test.o \
  -o conv_float_test.riscv
```

### Run Fixed16 Test

```bash
cd ~/chipyard/sims/verilator

make CONFIG=MyConvAccelConfig \
  run-binary \
  BINARY=~/elen90093-cnn-accelerator/tests/software/conv_test.riscv \
  TIMEOUT_CYCLES=100000000
```

### Run Float16 Test

```bash
cd ~/chipyard/sims/verilator

make CONFIG=MyConvAccelConfig \
  run-binary \
  BINARY=~/elen90093-cnn-accelerator/tests/software/conv_float_test.riscv \
  TIMEOUT_CYCLES=100000000
```

---

## Debugging Notes

Useful debug points:

```text
CONFIG accepted/rejected
KERNEL accepted/rejected
DATA accepted/rejected
window load request
window load response
tag allocation
tag release
windowReadyQ enqueue/dequeue
outputQ enqueue/dequeue
store request
store response
compute start
compute done
```

Common failure modes:

```text
SimpleHellaCacheIF exception:
  usually caused by unaligned 64-bit memory access

5x5 mismatch only:
  likely window index, padding, or sliding-copy bug

store mismatch every 4 outputs:
  likely 64-bit output packing order issue

hang in COMPUTE:
  likely tag leak, queue full/empty deadlock, or buffer state not released
```

---

## Current Limitations

- The pipeline still processes one output at a time internally, although four outputs are packed for store.
- The next major optimization is a 4-output tile pipeline.
- The PIPO window buffers use 25 elements each, matching the maximum 5x5 window.
- The design is optimized for a fixed 32x32 input and output shape.
- Float16 is supported but has higher hardware cost because it uses HardFloat units.
- More non-identity kernels and random tests should be added before final reporting.

---

## Next Optimization Direction

The next performance step is to move from:

```text
1 pipeline task = 1 output pixel
```

to:

```text
1 pipeline task = 4 adjacent output pixels
```

This would allow the accelerator to compute four adjacent outputs, pack them into one 64-bit word, and store them with one memory request.

For 5x5 convolution, a 4-output tile needs a maximum input tile of:

```text
5 rows x 8 columns = 40 input values
```

This increases the window buffer size but reduces per-output queue, scheduler, and store overhead.

Expected next-step design:

```text
tileBuf0[40]
tileBuf1[40]
4 fixed16 accumulators
4 output values packed into one 64-bit store
```

This is the recommended next branch after `feature/streaming-sliding-pipeline`.

---

## Repository Structure

```text
src/      Chisel and Scala source code for the accelerator
tests/    Software and hardware tests
docs/     Project notes and setup instructions
reports/  Report materials and figures
scripts/  Helper scripts
```

---

## Key File Locations

GitHub source:

```text
src/main/chisel/ConvAccelerator.scala
```

Chipyard compile target:

```text
~/chipyard/generators/myaccelerators/src/main/scala/ConvAccelerator.scala
```

Fixed16 test:

```text
tests/software/conv_test.c
```

Float16 test:

```text
tests/software/conv_float_test.c
```
