# \# ELEN90093 CNN Convolution Accelerator

# 

# \## Team Members

# \- PAK YAT HUI

# \- Teammate Name

# 

# \## Project Goal

# Design and implement a RISC-V convolution accelerator for matrix convolution with fixed-point support.

# 

# \## Repository Structure

# \- `src/`: Chisel source code

# \- `tests/`: software and hardware tests

# \- `docs/`: design notes, FSM, meeting notes

# \- `reports/`: report materials and figures

# \- `scripts/`: helper scripts

# 

# \## Workflow

# \- `main` branch must stay stable

# \- all development happens in feature branches

# \- merge to `main` through pull requests only

## Current Progress From PAK (Control Path Implemented)

### Accelerator FSM
We have implemented the initial control FSM for the convolution accelerator based on a RoCC interface.

FSM states:
- Idle
- Decode
- Load
- Compute
- Store
- Respond

### Instruction Design
The accelerator currently uses the funct field to distinguish commands:
- funct = 0 → LOAD
- funct = 1 → COMPUTE
- funct = 2 → STORE

### Implementation Details
- Based on Workshop 4 MyMAC structure (LazyRoCC)
- Command buffering via Queue(io.cmd, 1)
- FSM implemented in MyConvAccelModule
- Control signals:
  - mem_read_en (placeholder)
  - compute_en (placeholder)
  - mem_write_en (placeholder)
- Multi-cycle operations are currently simulated using counters

### TODO (Next Steps)
- Implement real memory interface (io.mem.req / resp)
- Integrate convolution datapath (MAC / sliding window)
- Support multiple sub-commands for load/config
- Add proper handshake (load_done / compute_done / store_done)
- Performance evaluation and benchmarking
