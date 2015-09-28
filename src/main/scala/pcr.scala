// See LICENSE for license details.

package uncore

abstract trait PCRParameters extends UsesParameters {
  val xLen = params(XLen)
  val nCores = params(NTiles)
  val coreIdBits = log2Up(params(NTiles))
  val addrBits = 12
}

abstract class PCRBundle extends Bundle with PCRParameters
abstract class PCRModule extends Module with PCRParameters

class PCRReq extends PCRBundle {
  val coreId = UInt(width = coreIdBits)
  val addr = UInt(width = addrBits)
  val cmd = UInt(width = CSR.SZ)
  val data = Bits(width = xLen)
}

class PCRResp extends PCRBundle {
  val broadcast = Bool()
  val coreId = UInt(width = coreIdBits)
  val addr = UInt(width = addrBits)
  val data = Bits(width = xLen)
}

class PCRIO extends PCRBundle {
  val req = new DecoupledIO(new PCRReq)
  val resp = new ValidIO(new PCRResp).flip
}

class PCRControl extends PCRModule {
  val io = new Bundle {
    val pcr_req = Vec(new DecoupledIO(new PCRReq)).flip
    val pcr_resp = new ValidIO(new PCRResp)
    val reset = Bool(OUTPUT)
  }

  // global registers
  val reg_time = Reg(width=xLen)

}
