class WithMyConvAccel extends Config((site, here, up) => {
  case BuildRoCC => up(BuildRoCC) ++ Seq(
    (p: Parameters) => {
      val conv = LazyModule(new MyConvAccel(OpcodeSet.custom0)(p))
      conv
    }
  )
})

class MyConvAccelConfig extends Config(
  new WithMyConvAccel ++
  new freechips.rocketchip.rocket.WithNHugeCores(1) ++
  new chipyard.config.AbstractConfig
)
