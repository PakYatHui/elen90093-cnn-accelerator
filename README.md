# ELEN90093 CNN Convolution Accelerator

## Team Members

- PAK YAT HUI
- Hanburger

## Project Overview

This project implements a RISC-V RoCC-based CNN convolution accelerator for 32x32 matrix convolution.

The accelerator started as a signed 16-bit fixed-point 8.8 convolution accelerator. The current development version extends the design with a cleaner command interface and a selectable data type path for both fixed-point 8.8 and IEEE-754 half-precision floating point (`Float16`).

The accelerator supports runtime kernel-size configuration for 1x1, 3x3, and 5x5 convolution kernels. It uses zero padding for boundary elements and communicates with the RISC-V core through a RoCC custom instruction interface.

## Version and Status

```text
Version: v0.3 - Interface Refactor and Float16 Datapath Extension
Branch: feature/accelerator-fsm
Status: Updated command interface tested; Fixed16 path preserved; Float16 path added for validation
```

The current version focuses on two major updates:

1. Refactoring the accelerator command interface from the old CONFIG / LOAD / COMPUTE / STORE model into a clearer CONFIG / KERNEL / DATA / COMPUTE / STORE model.
2. Extending the internal datapath so the accelerator can select between fixed-point 8.8 and Float16 computation.

The fixed-point path remains the baseline working path. The Float16 path has been added and should be validated further using different kernels and CNN-style workloads.

## Supported Features

- 32x32 input matrix
- 32x32 output matrix
- 1x1, 3x3, and 5x5 convolution kernels
- Runtime kernel-size configuration
- Runtime data-type configuration
- Signed 16-bit fixed-point 8.8 data path
- IEEE-754 half-precision Float16 data path
- Zero padding for boundary elements
- RoCC custom instruction interface
- Success/error response through `rd`
- Internal input buffer
- Internal kernel buffer
- Internal output buffer
- Real memory load through `io.mem.req` and `io.mem.resp`
- Real memory store through `io.mem.req` and `io.mem.resp`
- 64-bit full-width stores to avoid TileLink PutPartial transactions
- 25-term convolution datapath for maximum 5x5 kernels
- Runtime masking for smaller kernels
- Basic command-sequence checking
- Bias interface registers prepared for later bias support

## Current Limitations

- Bias address and bias enable are exposed in the interface, but bias computation is not implemented yet.
- If bias is enabled, the current design rejects the DATA command and returns an error.
- Float16 computation has been added using HardFloat modules, but it needs more testing with non-identity kernels.
- The current Float16 test is mainly a functional validation path, not a full CNN benchmark.
- The accelerator currently targets one 32x32 input matrix and one output matrix.
- Performance optimization is not the current focus; correctness and interface stability come first.

## Major Updates in v0.3

### Command interface refactor

The old command sequence was:

```text
CONFIG -> LOAD -> COMPUTE -> STORE
```

The new command sequence is:

```text
CONFIG -> KERNEL -> DATA -> COMPUTE -> STORE
```

This separates the accelerator configuration into clearer categories:

```text
CONFIG = kernel size, data type, bias enable
KERNEL = kernel weight address, bias address
DATA   = input data address, output data address
```

The DATA command replaces the old LOAD command at the software-interface level. Internally, the accelerator still reuses the existing load state to read input data and kernel weights into internal buffers.

### Data type extension

The accelerator now supports two data type modes:

```text
DATA_FIXED16 = 0
DATA_FLOAT16 = 1
```

The internal buffers were changed from signed 16-bit storage to raw 16-bit storage:

```scala
Reg(Vec(..., UInt(16.W)))
```

This allows the same memory layout to hold either:

```text
fixed-point 8.8 raw bits
Float16 raw bits
```

Fixed-point computation interprets the buffer values as signed 16-bit 8.8 values.

Float16 computation interprets the buffer values as IEEE-754 half-precision values and uses HardFloat-based floating-point multiply/add logic.

### Test update

Two software tests are now maintained:

```text
tests/software/conv_test.c
tests/software/conv_float_test.c
```

`conv_test.c` is the fixed-point 8.8 test.

`conv_float_test.c` is the Float16 test using `uint16_t` raw half-precision values.

## Main Accelerator File

GitHub project file:

```text
src/main/chisel/ConvAccelerator.scala
```

Chipyard compile location:

```text
~/chipyard/generators/myaccelerators/src/main/scala/ConvAccelerator.scala
```

The GitHub project file is used for version control. The Chipyard file is the one actually compiled by Chipyard.

When testing the accelerator in Chipyard, copy the GitHub file into the Chipyard compile location:

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

The CONFIG command sets the convolution mode.

`rs1` selects the kernel size:

```text
1 = 1x1 kernel
3 = 3x3 kernel
5 = 5x5 kernel
```

`rs2` stores config flags:

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
Command = KERNEL
rs1 = kernel weight address
rs2 = bias address
rd  = 1 for success, 0 for error
```

The KERNEL command stores the kernel weight address and bias address.

The bias address is recorded for future use. Bias computation is not currently active.

### DATA

```text
funct7 = 1
Command = DATA
rs1 = input data address
rs2 = output data address
rd  = 1 for success, 0 for error
```

The DATA command sets the input and output matrix addresses.

After the DATA command is accepted, the accelerator enters its internal memory-load state and reads:

```text
input matrix data
kernel weight data
```

### COMPUTE

```text
funct7 = 2
Command = COMPUTE
rs1 = unused
rs2 = unused
rd  = 1 for success, 0 for error
```

The COMPUTE command runs convolution using the selected data type.

### STORE

```text
funct7 = 3
Command = STORE
rs1 = unused
rs2 = unused
rd  = 1 for success, 0 for error
```

The STORE command writes the output matrix back to memory.

## Required Command Order

The required command order is:

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

### Fixed-point functional test

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

### Float16 functional test

```text
tests/software/conv_float_test.c
```

This test:

- stores Float16 values as raw `uint16_t` bits
- configures the accelerator for `DATA_FLOAT16`
- uses the CONFIG / KERNEL / DATA / COMPUTE / STORE command sequence
- runs 1x1, 3x3, and 5x5 identity-style convolution tests
- computes a CPU-side reference using Float32 helper conversion and half-precision rounding helpers
- compares hardware and software raw Float16 output bits

The Float16 test should be expanded with more kernels and edge cases before being treated as complete CNN validation.

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

### Copy the accelerator into Chipyard

```bash
cp ~/elen90093-cnn-accelerator/src/main/chisel/ConvAccelerator.scala \
~/chipyard/generators/myaccelerators/src/main/scala/ConvAccelerator.scala
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

`TIMEOUT_CYCLES=100000000` is used because the 5x5 software reference and Verilator simulation can take a long time.

## Repository Structure

```text
src/      Chisel and Scala source code for the accelerator
tests/    Software and hardware tests
docs/     Project notes, setup instructions, and accelerator documentation
reports/  Report materials and figures
scripts/  Helper scripts
```

## Next Steps

The next stage of the project should focus on two areas.

### 1. Find suitable CNN algorithms for this accelerator

The current accelerator is best suited for small convolution workloads with 1x1, 3x3, or 5x5 kernels on a 32x32 input matrix.

The next task is to identify CNN algorithms or layers that match this hardware structure. Good candidates include:

- simple image filtering layers
- edge detection kernels
- small CNN convolution layers
- LeNet-style early convolution layers
- lightweight 3x3 convolution workloads
- 1x1 pointwise convolution workloads

The goal is to choose an algorithm that can clearly demonstrate why the custom accelerator is useful.

### 2. Write different tests

The current tests mainly use identity-style kernels. More tests are needed to validate the accelerator more realistically.

Recommended new tests:

- non-identity 3x3 kernel test
- non-identity 5x5 kernel test
- negative-value fixed-point test
- negative-value Float16 test
- mixed positive and negative kernel test
- zero-padding boundary test
- all-zero kernel test
- all-one kernel test
- random small-value input and kernel test
- CPU reference comparison for each test
- cycle-count comparison for fixed16 and Float16 modes

These tests should be added before final evaluation and report writing.
