# ELEN90093 CNN Convolution Accelerator

## Team Members

- PAK YAT HUI
- Hanburger

## Project Overview

This project implements a RISC-V RoCC-based CNN convolution accelerator for 32x32 matrix convolution.

The accelerator originally supported signed 16-bit fixed-point 8.8 convolution. The current version extends the interface and datapath structure to support both fixed-point 8.8 and IEEE-754 half-precision floating point (`float16`) data formats.

The accelerator supports runtime kernel-size configuration for 1x1, 3x3, and 5x5 kernels, zero padding, and a RoCC custom instruction interface.

## Version and Status

```text
Version: v0.3 - Float16 Interface and Datapath Extension
Branch: feature/accelerator-fsm
Status: Fixed16 and Float16 tests compile and run in Chipyard simulation
```

The accelerator has been compiled in Chipyard simulation with the updated command interface.

The updated interface has been tested using the fixed-point functional test. A new `conv_float_test.c` test has also been added for the Float16 data path.

## Supported Features

- 32x32 input matrix
- 32x32 output matrix
- 1x1, 3x3, and 5x5 convolution kernels
- 16-bit signed fixed-point 8.8 data path
- 16-bit IEEE-754 half-precision Float16 data path
- Zero padding for boundary elements
- Runtime kernel-size configuration
- Runtime data-type configuration
- Bias control interface through `biasEnableReg` and `biasAddrReg`
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

## Current Limitations

- Bias is currently exposed through the interface, but bias computation is not yet implemented.
- If `biasEnableReg = 1`, the current design rejects the DATA command and returns an error.
- Float16 computation uses HardFloat modules and should be validated with simple kernels before using more complex filters.
- Float16 and fixed16 currently share the same 16-bit memory layout, so memory load and store width remain unchanged.

## Major Updates in v0.3

- Reworked the RoCC software interface from the old four-command flow:

```text
CONFIG -> LOAD -> COMPUTE -> STORE
```

- Updated the command flow to the new five-command interface:

```text
CONFIG -> KERNEL -> DATA -> COMPUTE -> STORE
```

- Changed `CONFIG` to configure kernel size, data type, and bias enable.
- Added a new `KERNEL` command to configure kernel weight address and bias address.
- Changed the old `LOAD` command into a `DATA` command.
- `DATA` now sets the input data address and output data address, then reuses the existing internal load state.
- Preserved the existing `sLoad`, `sCompute`, and `sStore` flow as much as possible.
- Changed accelerator buffers from signed 16-bit values to raw 16-bit values:

```scala
Reg(Vec(..., UInt(16.W)))
```

- Added runtime data-type selection:

```text
DATA_FIXED16 = 0
DATA_FLOAT16 = 1
```

- Added a Float16 computation path using HardFloat.
- Added `conv_float_test.c` for Float16 functional testing.
- Kept the original `conv_test.c` for fixed-point 8.8 testing.

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

```bash
cp ~/elen90093-cnn-accelerator/src/main/chisel/ConvAccelerator.scala \
~/chipyard/generators/myaccelerators/src/main/scala/ConvAccelerator.scala
```

## Custom Instruction Interface

The accelerator uses the `funct7` field to select commands.

### CONFIG

```text
funct7 = 0
Command = CONFIG
rs1 = kernel size
rs2 = config flags
rd  = 1 for success, 0 for error
```

`rs2` config flag layout:

```text
rs2[1:0] = data type
rs2[2]   = bias enable
```

Data type encoding:

```text
0 = DATA_FIXED16
1 = DATA_FLOAT16
```

### DATA

```text
funct7 = 1
Command = DATA
rs1 = input data address
rs2 = output data address
rd  = 1 for success, 0 for error
```

The DATA command replaces the old LOAD command at the software-interface level.
Internally, the accelerator still enters the existing memory-load state to read input data and kernel weights into internal buffers.

### COMPUTE

```text
funct7 = 2
Command = COMPUTE
rs1 = unused
rs2 = unused
rd  = 1 for success, 0 for error
```

### STORE

```text
funct7 = 3
Command = STORE
rs1 = unused
rs2 = unused
rd  = 1 for success, 0 for error
```

### KERNEL

```text
funct7 = 4
Command = KERNEL
rs1 = kernel weight address
rs2 = bias address
rd  = 1 for success, 0 for error
```

The bias address is recorded for future bias support. Bias computation is not enabled yet.

## Required Command Order

```c
conv_config(kernel_size, config_flags);
conv_kernel(kernel_addr, bias_addr);
conv_data(input_addr, output_addr);
conv_compute();
conv_store();
```

For fixed-point 8.8 without bias:

```c
conv_config(kernel_size, CONFIG_FLAGS(DATA_FIXED16, 0));
conv_kernel(kernel_buf, 0);
conv_data(input_buf, hw_output);
conv_compute();
conv_store();
```

For Float16 without bias:

```c
conv_config(kernel_size, CONFIG_FLAGS(DATA_FLOAT16, 0));
conv_kernel(kernel_buf, 0);
conv_data(input_buf, hw_output);
conv_compute();
conv_store();
```

The software wrappers use RoCC macros with destination-register support:

```text
CONFIG  -> ROCC_INSTRUCTION_DSS
KERNEL  -> ROCC_INSTRUCTION_DSS
DATA    -> ROCC_INSTRUCTION_DSS
COMPUTE -> ROCC_INSTRUCTION_D
STORE   -> ROCC_INSTRUCTION_D
```

This ensures that the custom instruction sets `xd = 1` when a response through `rd` is expected.

## Software Tests

### Fixed-point test

```text
tests/software/conv_test.c
```

This test:

- initializes 32x32 fixed-point 8.8 input data
- configures the accelerator for `DATA_FIXED16`
- sets the kernel address through the KERNEL command
- sets input and output addresses through the DATA command
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

### Float16 test

```text
tests/software/conv_float_test.c
```

This test:

- stores Float16 values as raw `uint16_t` bits
- configures the accelerator for `DATA_FLOAT16`
- uses the new CONFIG / KERNEL / DATA / COMPUTE / STORE command sequence
- runs 1x1, 3x3, and 5x5 identity-style convolution tests
- computes a CPU reference using Float32 helper conversion and half-precision rounding helpers
- compares hardware and software raw Float16 output bits

Expected final result:

```text
=== Final Result: ALL PASS ===
```

## Build and Run Instructions

### Compile the fixed-point test

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

### Compile the Float16 test

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

### Build the Chipyard simulator

```bash
cd ~/chipyard/sims/verilator

make clean CONFIG=MyConvAccelConfig
make CONFIG=MyConvAccelConfig
```

### Run the fixed-point test

```bash
cd ~/chipyard/sims/verilator && \
make CONFIG=MyConvAccelConfig \
  run-binary \
  BINARY=~/elen90093-cnn-accelerator/tests/software/conv_test.riscv \
  TIMEOUT_CYCLES=100000000
```

### Run the Float16 test

```bash
cd ~/chipyard/sims/verilator && \
make CONFIG=MyConvAccelConfig \
  run-binary \
  BINARY=~/elen90093-cnn-accelerator/tests/software/conv_float_test.riscv \
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

- Add bias load and bias addition to the compute datapath.
- Add more diverse Float16 kernels instead of identity-only kernels.
- Add edge-case tests for negative Float16 values.
- Add overflow, underflow, NaN, Inf, and rounding behaviour tests for Float16.
- Clean up debug `printf` statements in `ConvAccelerator.scala`.
- Keep only essential STORE / RESP debug messages if needed.
- Improve performance by allowing multiple outstanding memory requests.
- Optimize or pipeline the Float16 compute datapath if performance becomes a concern.
- Prepare FSM, datapath, and memory-flow diagrams for the final report.
- Update final report and presentation materials.
