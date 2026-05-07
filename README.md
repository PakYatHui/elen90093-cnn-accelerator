# ELEN90093 CNN Convolution Accelerator

## Team Members

- PAK YAT HUI
- Hanburger

## Project Overview

This project implements a RISC-V RoCC-based CNN convolution accelerator for fixed-point matrix convolution.

The accelerator supports 32x32 convolution using signed 16-bit fixed-point 8.8 data. It supports runtime kernel-size configuration for 1x1, 3x3, and 5x5 kernels, zero padding, and a RoCC custom instruction interface.

## Version and Status

```text
Version: v0.2 - Functional RoCC Convolution Accelerator
Branch: feature/accelerator-fsm
Status: Functional test passed in Chipyard simulation
```

The current version has been compiled and functionally tested in Chipyard simulation.

The accelerator has passed the current `conv_test.c` functional test, including 1x1, 3x3, and 5x5 convolution cases against a CPU software reference implementation.

## Supported Features

- 32x32 input matrix
- 32x32 output matrix
- 1x1, 3x3, and 5x5 convolution kernels
- 16-bit signed fixed-point 8.8 data
- Zero padding for boundary elements
- Runtime kernel-size configuration
- RoCC custom instruction interface
- Success/error response through `rd`
- Internal input buffer
- Internal kernel buffer
- Internal output buffer
- Real memory load through `io.mem.req` and `io.mem.resp`
- Real memory store through `io.mem.req` and `io.mem.resp`
- 64-bit full-width stores to avoid TileLink PutPartial transactions
- 25-MAC datapath for maximum 5x5 convolution
- Runtime masking for smaller kernels
- Basic command-sequence checking

## Major Updates in v0.2

- Implemented full CONFIG / LOAD / COMPUTE / STORE accelerator flow.
- Added runtime kernel-size support for 1x1, 3x3, and 5x5 kernels.
- Added internal input, kernel, and output buffers.
- Added memory load using `io.mem.req` and `io.mem.resp`.
- Added memory store using 64-bit full-width `M_XWR` requests.
- Fixed STORE completion by waiting for `io.mem.resp.valid` before returning a RoCC response.
- Added `xd`-aware RoCC response handling.
- Added functional software tests using `rocc.h` macros.
- Added CPU reference comparison for correctness validation.
- Added cycle-count measurement using `rdcycle`.

## Main Accelerator File

GitHub project file:

```text
src/main/chisel/ConvAccelerator.scala
```

Chipyard compile location:

```text
~/chipyard/generators/myaccelerators/src/main/scala/ConvAccelerator.scala
```

Important:

The GitHub project file is used for version control.

The Chipyard file is the one actually compiled by Chipyard.

When testing the accelerator in Chipyard, copy the GitHub file into the Chipyard compile location.

## Custom Instruction Interface

The accelerator uses the `funct7` field to select commands.

```text
funct7 = 0
Command = CONFIG
rs1 = kernel size
rs2 = output address
rd = 1 for success, 0 for error

funct7 = 1
Command = LOAD
rs1 = input address
rs2 = kernel address
rd = 1 for success, 0 for error

funct7 = 2
Command = COMPUTE
rs1 = unused
rs2 = unused
rd = 1 for success, 0 for error

funct7 = 3
Command = STORE
rs1 = unused
rs2 = unused
rd = 1 for success, 0 for error
```

Required command order:

```c
conv_config(kernel_size, output_addr);
conv_load(input_addr, kernel_addr);
conv_compute();
conv_store();
```

The software wrappers use RoCC macros with destination-register support:

```text
CONFIG  -> ROCC_INSTRUCTION_DSS
LOAD    -> ROCC_INSTRUCTION_DSS
COMPUTE -> ROCC_INSTRUCTION_D
STORE   -> ROCC_INSTRUCTION_D
```

This ensures that the custom instruction sets `xd = 1` when a response through `rd` is expected.

## Software Tests

Main functional test:

```text
tests/software/conv_test.c
```

This test:

- initializes 32x32 input data
- configures the accelerator
- loads input and kernel data
- runs hardware convolution
- stores accelerator output back to memory
- computes a CPU software reference result
- compares all 1024 output elements
- tests 1x1, 3x3, and 5x5 kernels
- reports hardware and software cycle counts

Expected final result:

```text
=== Final Result: ALL PASS ===
```

## Build and Run Instructions

Compile the C test program:

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

riscv64-unknown-elf-objdump -d conv_test.riscv > conv_test.disasm
```

Copy the accelerator source into the Chipyard compile location:

```bash
cp ~/elen90093-cnn-accelerator/src/main/chisel/ConvAccelerator.scala \
~/chipyard/generators/myaccelerators/src/main/scala/ConvAccelerator.scala
```

Build the Chipyard simulator:

```bash
cd ~/chipyard/sims/verilator

make clean CONFIG=MyConvAccelConfig
make CONFIG=MyConvAccelConfig
```

Run the functional test:

```bash
cd ~/chipyard/sims/verilator

make CONFIG=MyConvAccelConfig \
  run-binary \
  BINARY=~/elen90093-cnn-accelerator/tests/software/conv_test.riscv \
  TIMEOUT_CYCLES=100000000
```

`TIMEOUT_CYCLES=100000000` is used because the 5x5 software reference and accelerator simulation can take a long time in Verilator.

## Repository Structure

```text
src/      Chisel and Scala source code for the accelerator.
tests/    Software and hardware tests.
docs/     Project notes, setup instructions, and accelerator documentation.
reports/  Report materials and figures.
scripts/  Helper scripts.
```

## Next Steps

- Clean up debug `printf` statements in `ConvAccelerator.scala`.
- Keep only essential STORE / RESP debug messages if needed.
- Add more diverse test kernels instead of identity-only kernels.
- Add edge-case tests for negative fixed-point values.
- Add overflow and truncation behaviour tests.
- Improve performance by allowing multiple outstanding memory requests.
- Optimize or pipeline the compute datapath if performance becomes a concern.
- Prepare FSM, datapath, and memory-flow diagrams for the final report.
- Update final report and presentation materials.
