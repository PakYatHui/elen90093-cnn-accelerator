package chipyard

// =============================================================================
// ConvAccelTopConfig.scala
//
// Top-level SoC config for the ELEN90093 CNN Convolution Accelerator.
//
// Usage:
//   This file must be copied manually to:
//   ~/chipyard/generators/chipyard/src/main/scala/config/
//
//   It cannot be symlinked with the rest of the project because it belongs
//   to package chipyard, not package myaccelerators.
//
//   To elaborate (generate Verilog):
//     cd ~/chipyard
//     make CONFIG=MyConvAccelConfig verilog
//
//   To run simulation:
//     cd ~/chipyard/sims/verilator
//     make CONFIG=MyConvAccelConfig
// =============================================================================

import org.chipsalliance.cde.config._
import myaccelerators._

// Full SoC config: single Rocket core + CNN convolution accelerator
class MyConvAccelConfig extends Config(
  new WithMyConvAccel ++
  new freechips.rocketchip.rocket.WithNHugeCores(1) ++
  new chipyard.config.AbstractConfig
)