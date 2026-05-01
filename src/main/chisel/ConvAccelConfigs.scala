package myaccelerators

// =============================================================================
// ConvAccelConfigs.scala
//
// Config fragments for the ELEN90093 CNN Convolution Accelerator.
//
// Usage:
//   1. Copy this file to:
//      ~/chipyard/generators/myaccelerators/src/main/scala/
//   2. In TutorialConfigs.scala (or any Chipyard top-level config), add:
//        import myaccelerators._
//        class MyConvAccelConfig extends Config(
//          new WithMyConvAccel ++
//          new chipyard.config.AbstractConfig
//        )
// =============================================================================

import org.chipsalliance.cde.config._
import org.chipsalliance.diplomacy.lazymodule._
import freechips.rocketchip.tile._

// -----------------------------------------------------------------------------
// WithMyConvAccel
//
// Config fragment that attaches the MyConvAccel RoCC accelerator to a Rocket
// core using the custom0 opcode set (funct7 field selects CONFIG/LOAD/COMPUTE/STORE).
// Mix this fragment into any Chipyard Config to enable the accelerator.
// -----------------------------------------------------------------------------
class WithMyConvAccel extends Config((site, here, up) => {
  case BuildRoCC => up(BuildRoCC) ++ Seq(
    (p: Parameters) => {
      val convAccel = LazyModule(new MyConvAccel(OpcodeSet.custom0)(p))
      convAccel
    }
  )
})
