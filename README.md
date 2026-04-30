# ELEN90093 CNN Convolution Accelerator

## Team Members

- PAK YAT HUI
- Teammate Name

## Project Goal

This project implements a RISC-V RoCC-based CNN convolution accelerator for fixed-point matrix convolution.

The current accelerator supports:

- 32x32 input matrix
- 32x32 output matrix
- 1x1, 3x3, and 5x5 convolution kernels
- 16-bit signed fixed-point 8.8 data
- Zero padding
- Runtime kernel size configuration
- RoCC custom instruction interface
- Success/error response through rd

## Current Project Status

The accelerator has been updated from the original control-path-only version to a configurable convolution accelerator.

The current version has been compiled successfully in Chipyard.

Implemented features:

- RoCC LazyRoCC accelerator structure
- CONFIG / LOAD / COMPUTE / STORE command flow
- Runtime kernel size selection
- Support for 1x1, 3x3, and 5x5 kernels
- Internal input buffer
- Internal kernel buffer
- Internal output buffer
- Real memory load through io.mem.req and io.mem.resp
- Real memory store through io.mem.req
- 25-MAC datapath for maximum 5x5 convolution
- Runtime masking for smaller kernels
- Zero padding for boundary elements
- Basic command sequence checking
- Binary success/error return value

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

The accelerator uses the funct7 field to select commands.

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

## Repository Structure

```text
src/      Chisel and Scala source code for the accelerator.
tests/    Software and hardware tests.
docs/     Project notes, setup instructions, and accelerator documentation.
reports/  Report materials and figures.
scripts/  Helper scripts.
```

## Current Branch

```text
feature/accelerator-fsm
```

## Next Steps

- Write C test program using RoCC macros
- Test 1x1, 3x3, and 5x5 convolution
- Compare accelerator output with CPU reference output
- Measure cycle count using rdcycle
- Compare software-only convolution against accelerator convolution
- Prepare FSM diagram
- Prepare datapath diagram
- Prepare memory flow diagram
- Update report and presentation materials
