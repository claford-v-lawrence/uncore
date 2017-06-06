// See LICENSE for license details.

package uncore
import Chisel._
import cde.{Parameters, Field}
import junctions._

case object TCMemTransactors extends Field[Int]
case object TCTagTransactors extends Field[Int]

trait HasTCParameters extends HasCoherenceAgentParameters
    with HasCacheParameters with HasTagParameters
{
  val uncached = true

  val nMemAcquireTransactors = p(TCMemTransactors)
  val nMemReleaseTransactors = if(uncached) 0 else 1
  val nMemTransactors = nMemReleaseTransactors + nMemAcquireTransactors
  val nTagTransactors = p(TCTagTransactors)

  val refillCycles = outerDataBeats

  require(outerDataBits == rowBits)
  require(p(CacheBlockBytes) * tgBits / 8 <= rowBits) // limit the data size of tag operation to a row
  require(outerTLId == p(TLId))
}

abstract class TCModule(implicit val p: Parameters) extends Module with HasTCParameters
abstract class TCBundle(implicit val p: Parameters) extends ParameterizedBundle()(p) with HasTCParameters

trait HasTCId extends HasTCParameters {
  val id = UInt(width = log2Up(nMemTransactors + nTagTransactors + 1))
}
trait HasTCTagFlag   extends HasTCParameters { val tcnt = UInt(width = log2Up(outerDataBeats+1)) }   // counting the rows with tags
trait HasTCTag       extends HasTCParameters { val tag  = UInt(width = tagBits) }
trait HasTCHit       extends HasTCParameters { val hit  = Bool() }

class TCIncoherence(implicit p: Parameters) extends CoherenceMetadata()(p)
class TCMetadata(implicit p: Parameters) extends Metadata()(p) with HasTCParameters with HasTCTagFlag
{
  val coh = new TCIncoherence
  val state = UInt(width=2) // MSI (0=invalid, 1=clean, 3=dirty)
  def isValid(dummy:Int=0) = state =/= TCMetadata.Invalid
  override def cloneType = new TCMetadata().asInstanceOf[this.type]
}

object TCMetadata {
  def apply(tag:UInt, tcnt:UInt, state: UInt)(implicit p: Parameters) = {
    val meta = Wire(new TCMetadata)
    meta.tag := tag
    meta.state := state
    meta.tcnt := tcnt
    meta
  }
  def Invalid = UInt("b00")
  def Clean = UInt("b01")
  def Dirty = UInt("b11")
  def onReset(implicit p: Parameters) = TCMetadata(UInt(0), UInt(0), UInt(0))(p)
}

class TCMetaReadReq(implicit p: Parameters) extends MetaReadReq()(p) with HasTCTag with HasTCId
class TCMetaWriteReq(implicit p: Parameters) extends MetaWriteReq[TCMetadata](new TCMetadata)(p)
class TCMetaReadResp(implicit p: Parameters) extends TCBundle()(p) with HasTCId with HasTCHit
{
  val meta = new TCMetadata
  val way_en = UInt(width = nWays)
}

class TCMetaIO(implicit p: Parameters) extends TCBundle()(p)
{
  val read = Decoupled(new TCMetaReadReq)
  val write = Decoupled(new TCMetaWriteReq)
  val resp = Valid(new TCMetaReadResp).flip
  override def cloneType = new TCMetaIO().asInstanceOf[this.type]
}

trait HasTCRow extends HasTCParameters  { val row  = UInt(width = log2Up(refillCycles)) }
trait HasTCData extends HasTCParameters { val data = UInt(width = rowBits) }
trait HasTCByteMask extends HasTCParameters { val mask = UInt(width = rowBytes) }
trait HasTCBitMask extends HasTCParameters { val mask = UInt(width = rowBits) }
trait HasTCAddr extends HasTCParameters { val addr = UInt(width=p(PAddrBits)) }

class TCDataReadReq(implicit p: Parameters) extends TCBundle()(p) with HasTCId with HasTCRow
{
  val idx = Bits(width = idxBits)
  val way_en = Bits(width = nWays)
}
class TCDataWriteReq(implicit p: Parameters) extends TCDataReadReq()(p) with HasTCData with HasTCByteMask
class TCDataReadResp(implicit p: Parameters) extends TCBundle()(p)
    with HasTCId with HasTCData

class TCDataIO(implicit p: Parameters) extends TCBundle()(p) {
  val read = Decoupled(new TCDataReadReq)
  val write = Decoupled(new TCDataWriteReq)
  val resp = Valid(new TCDataReadResp).flip
  override def cloneType = new TCDataIO().asInstanceOf[this.type]
}

class TCWBReq(implicit p: Parameters) extends TCDataReadReq()(p) with HasTCTag

object TCTagOp {
  def nBits = 3
  def R     = UInt("b000") // read if hit
  def F     = UInt("b001") // force a read and fetch if miss
  def FL    = UInt("b011") // force a read and lock it
  def W     = UInt("b100") // write a line
  def C     = UInt("b101") // create an empty line
  def CL    = UInt("b111") // create an empty line and lock it
  def I     = UInt("b110") // invalidate a line
  def isWrite(t:UInt) =  t(2)
  def isLock(t:Uint) = t(2) && t(1)
}

class TCTagRequest(implicit p: Parameters) extends TCBundle()(p)
    with HasTCId with HasTCData with HasTCBitMask with HasTCAddr
{
  val op   = UInt(width=TCTagOp.nBits)
}

class TCTagResp(implicit p: Parameters) extends TCBundle()(p) with HasTCId
    with HasTCHit with HasTCData with HasTCTagFlag

class TCTagXactIO(implicit p: Parameters) extends TCBundle()(p) {
  val req = Decoupled(new TCTagRequest)
  val resp = Valid(new TCTagResp).flip
}

////////////////////////////////////////////////////////////////
// tag cache metadata array

class TCMetadataArray(implicit p: Parameters) extends TCModule()(p) {
  val io = new TCMetaIO().flip

  val replacer = new RandomReplacement(nWays)
  val ren = io.read.fire()
  val onReset = () => TCMetadata.onReset
  val meta = Module(new MetadataArray[TCMetadata](onReset))
  meta.io.read <> io.read
  meta.io.read.bits.way_en := ~UInt(0,nWays)
  meta.io.write <> io.write

  val s1_read_valid = Reg(next = ren)
  val s1_id         = RegEnable(io.read.bits.id, ren)
  val s1_tag        = RegEnable(io.read.bits.tag, ren)
  val s1_match_way  = Vec(meta.io.resp.map(m => m.tag === s1_tag && m.isValid())).toBits

  val s2_match_way  = RegEnable(s1_match_way, s1_read_valid)
  val s2_repl_way   = RegEnable(replacer.way, s1_read_valid)
  val s2_hit        = s2_match_way.orR
  val s2_meta       = RegEnable(meta.io.resp, s1_read_valid)

  when(s1_read_valid && !s1_match_way.orR) {replacer.miss}

  io.resp.valid         := Reg(next = s1_read_valid)
  io.resp.bits.id       := RegEnable(s1_id, s1_read_valid)
  io.resp.bits.hit      := s2_hit
  io.resp.bits.way_en   := Mux(s2_hit, s2_match_way, UIntToOH(s2_repl_way))
  io.resp.bits.meta     := Mux(s2_hit, s2_meta(OHToUInt(s2_match_way)), s2_meta(s2_repl_way))
}

////////////////////////////////////////////////////////////////
// tag cache data array

class TCDataArray(implicit p: Parameters) extends TCModule()(p) {
  val io = new TCDataIO().flip

  val array = SeqMem(nWays*nSets*refillCycles, Vec(rowBits/8, Bits(width=8)))
  val ren = io.read.fire()
  val raddr = Cat(OHToUInt(io.read.bits.way_en), io.read.bits.idx, io.read.bits.row)
  val waddr = Cat(OHToUInt(io.write.bits.way_en), io.write.bits.idx, io.write.bits.row)
  val wdata = Vec.tabulate(rowBytes)(i => io.write.bits.data(8*(i+1)-1,8*i))
  val wmask = io.write.bits.mask.toBools
  when (io.write.valid) { array.write(waddr, wdata, wmask) }

  val s1_data       = array.read(raddr, ren).toBits
  val s1_read_valid = Reg(next = ren)
  val s1_id         = RegEnable(io.read.bits.id, ren)

  io.resp.valid     := Reg(next = s1_read_valid)
  io.resp.bits.id   := RegEnable(s1_id, s1_read_valid)
  io.resp.bits.data := RegEnable(s1_data, s1_read_valid)

  io.read.ready     := !io.write.valid
  io.write.ready    := Bool(true)
}

////////////////////////////////////////////////////////////////
// Tag Cache Writeback Unit

class TCWritebackUnit(id: Int)(implicit p: Parameters) extends TCModule()(p) with HasTileLinkParameters {
  val io = new Bundle {
    val req  = Decoupled(new TCWBReq).flip
    val data = new TCDataIO
    val tl   = new ClientUncachedTileLinkIO()
    val wb_addr = UInt(OUTPUT, width=tlBlockAddrBits)
    val addr_block = Vec(nTagTransactors, UInt(width=tlBlockAddrBits)).asInput
    val addr_match = Vec(nTagTransactors, Bool()).asOutput
    val mtActive = UInt(INPUT, nMemTransactors)
  }

  // check          empty line, check whether it is safe to avoid writeback
  // read           read non-empty line from data array
  // write          write line to memory

  val s_IDLE :: s_READ :: s_READ_DONE :: s_WRITE :: s_GNT :: Nil = Enum(UInt(), 6)
  val state = Reg(init = s_IDLE)

  val data_buffer = Reg(init=Vec.fill(refillCycles)(UInt(0, rowBits)))
  val (read_cnt, read_done) = Counter(io.data.read.fire() && state === s_READ, refillCycles)
  val (write_cnt, write_done) = Counter(io.data.resp.valid, refillCycles)
  val (tl_cnt, tl_done) = Counter(state === s_WRITE && io.tl.acquire.fire(), outerDataBeats)

  val xact = io.req.bits

  io.req.ready := tl_done

  io.data.read.valid := state === s_READ
  io.data.read.bits := xact
  io.data.read.bits.id := UInt(id)
  io.data.read.bits.row := read_cnt

  io.data.write.valid := Bool(false)

  when(state === s_IDLE) {
    data_buffer := Vec.fill(refillCycles)(UInt(0, rowBits))
  }
  when(io.data.resp.valid) {
    data_buffer(write_cnt) := io.data.resp.bits.data
  }

  io.tl.acquire.valid := state === s_WRITE
  val tl_addr:UInt = Cat(xact.tag, xact.idx, tl_cnt, UInt(0, rowOffBits))
  io.tl.acquire.bits :=
    PutBlock(
      UInt(nMemTransactors+id),
      tlGetBlockAddr(tl_addr),
      tlGetBeatAddr(tl_addr),
      data_buffer(tl_cnt)
    )

  io.tl.grant.ready := state === s_GNT

  when(state === s_IDLE && io.req.valid) {
    state := s_READ
  }
  when(state === s_READ && read_done) {
    state := s_READ_DONE
  }
  when(state === s_READ_DONE && write_done) {
    state := s_WRITE
  }
  when(state === s_WRITE && tl_done) {
    state := s_GNT
  }
  when(state === s_GNT && io.tl.grant.valid) {
    state := s_IDLE
  }

}

////////////////////////////////////////////////////////////////
// Tag Transaction Tracker

class TCTagXactTracker(id: Int)(implicit p: Parameters) extends TCModule()(p) with HasTileLinkParameters {
  val io = new Bundle {
    val xact = new TCTagXactIO().flip
    val meta = new TCMetaIO
    val data = new TCDataIO
    val wb   = new TCWBIO
    val tl   = new ClientUncachedTileLinkIO()
    val match_addr = UInt(OUTPUT,tl.tlBlockAddrBits)
    val match_fetch = Bool(INPUT)
    val match_create = Bool(INPUT)
    val create = Bool(OUTPUT)
  }

  // meta.R             always read metadata first
  // data.FWB           fetch and write back, fatch when F+R/W.miss
  //                    WB when F+R/W.miss and C needs replace write-back
  // data.R             read data array when W.h and F+R/R.h.t=1
  // data.BW            write a whole cache block in data array when F+R/W.m and C
  // data.RW            write a row in data array when W.h
  // data.C             check the whole cache line to see if potentially tagFlag reset
  // meta.W             write metadata when F.m, C, W.m and W.h.t!=t'

  val s_IDLE :: s_M_R_REQ :: s_M_R_RESP :: s_D_FWB_REQ :: s_D_FWB_RESP :: s_D_R_REQ :: s_D_R_RESP :: s_D_BW :: s_D_RW :: s_D_C_REQ :: s_D_C_RESP :: s_M_W :: Nil = Enum(UInt(), 12)
  val state = Reg(init = s_IDLE)

  // internal signals
  val xact_queue = Queue(io.xact.req, nMemTransactors, true) // enforce temporal order
  val xact = RegEnable(xact_queue.bits, xact_queue.fire())
  val byte_mask = Vec((0 until rowBytes).map(i => xact.mask(i*8+7, i*8).orR)).toBits
  val idx = xact.addr(idxBits+blockOffBits-1, blockOffBits)
  val way_en = RegEnable(io.meta.resp.bits.way_en, state === s_M_R_RESP && io.meta.resp.valid)
  val row = xact.addr(blockOffBits-1,rowOffBits)
  // break meta as workaround, do not really undersatnd the NO DEFAULT WIRE faults here
  //val meta = Reg(new TCMetadata)
  val meta_tag = Reg(init=UInt(0,tagBits))
  val meta_state = Reg(init=TCMetadata.Invalid)
  val meta_tagFlag = Reg(init=Bool(false))
  val fetch_tagFlag = Reg(init=Bool(false))
  val addrTag = xact.addr >> untagBits
  val (data_cnt, data_done) = Counter((state === s_D_BW && io.data.write.fire()) ||
                                      (state === s_D_C_REQ && io.data.read.fire()), refillCycles)
  val write_tag = xact.data.orR
  val writeback_sent = Reg(init=Bool(false))
  val writeback_done = Reg(init=Bool(false))
  val fetch_sent = Reg(init=Bool(false))
  val fetch_done = Reg(init=Bool(false))
  val fetch_buf = Reg(Vec(outerDataBeats, UInt(width=outerDataBits)))
  val (fetch_cnt, fetch_cnt_done) = Counter(state === s_D_FWB_RESP && io.tl.grant.fire(), outerDataBeats)
  val (_, check_done) = Counter((state === s_D_C_REQ || state === s_D_C_RESP) &&
                                        io.data.resp.valid, refillCycles)

  io.match_addr := xact.addr >> tlBlockOffsetBits
  io.create := io.xact.resp.valid && xact.op === TCTagOp.Create

  // transaction request
  xact_queue.ready := state === s_IDLE

  // transaction response
  io.xact.resp.bits.id := xact.id
  io.xact.resp.bits.hit := Bool(true)
  io.xact.resp.bits.tagFlag := meta_tagFlag
  io.xact.resp.bits.data := UInt(0)
  io.xact.resp.bits.tagUpdate := Bool(false)
  io.xact.resp.valid := Bool(false)

  // metadata read
  io.meta.read.bits.id := UInt(id)
  io.meta.read.bits.idx := idx
  io.meta.read.bits.tag := addrTag
  io.meta.read.valid := state === s_M_R_REQ

  // metadata write
  io.meta.write.bits.idx := idx
  io.meta.write.bits.way_en := way_en
  io.meta.write.bits.data := TCMetadata(meta_tag, meta_tagFlag, meta_state)
  io.meta.write.valid := state === s_M_W

  // data array read
  io.data.read.bits.id := UInt(id)
  io.data.read.bits.row := Mux(state === s_D_C_REQ, data_cnt, row)
  io.data.read.bits.idx := idx
  io.data.read.bits.way_en := way_en
  io.data.read.valid := state === s_D_R_REQ || state === s_D_C_REQ

  // data read response

  // data array write
  io.data.write.bits.id := UInt(id)
  io.data.write.bits.row := Mux(state === s_D_BW, data_cnt, row)
  io.data.write.bits.idx := idx
  io.data.write.bits.way_en := way_en
  io.data.write.bits.data := Mux(state === s_D_BW, fetch_buf(data_cnt), xact.data)
  io.data.write.bits.mask := Mux(state === s_D_BW, ~UInt(0,rowBytes), byte_mask)
  io.data.write.valid := state === s_D_RW || state === s_D_BW

  // write-back
  val wb_tag = Reg(UInt(width=tagBits))
  val wb_empty = Reg(Bool())
  io.wb.req.bits.id := UInt(id)
  io.wb.req.bits.row := row
  io.wb.req.bits.idx := idx
  io.wb.req.bits.way_en := way_en
  io.wb.req.bits.tag := wb_tag
  io.wb.req.bits.empty := wb_empty && !tgHelper.is_top(xact.addr)
  io.wb.req.valid := state === s_D_FWB_RESP && !writeback_sent

  when(state === s_D_FWB_REQ) {
    when(meta_state =/= TCMetadata.Dirty) { // writeback only dirty lines
      writeback_sent := Bool(true)
      writeback_done := Bool(true)
    }.otherwise{
      wb_tag := meta_tag
      wb_empty := !meta_tagFlag
    }
  }
  when(state === s_D_FWB_RESP) {
    when(io.wb.req.fire()) {
      writeback_sent := Bool(true)
    }
    when(io.wb.resp.valid) {
      writeback_done := Bool(true)
    }
    when(writeback_done && fetch_done) {
      writeback_sent := Bool(false)
      writeback_done := Bool(false)
    }
  }

  // fetch
  io.tl.acquire.bits := GetBlock(UInt(id), tlGetBlockAddr(xact.addr))
  io.tl.acquire.valid := state === s_D_FWB_RESP && !fetch_sent
  io.tl.grant.ready := state === s_D_FWB_RESP && !fetch_done

  // meta check
  when((state === s_D_C_REQ || state === s_D_C_RESP) && io.data.resp.valid) {
    meta_tagFlag := meta_tagFlag || io.data.resp.bits.data =/= UInt(0)
  }

  // data array update function
  def beat_data_update(tl_data:UInt, index:UInt) = {
    Mux(index === tlGetBeatAddr(xact.addr), (tl_data & ~xact.mask) | xact.data, tl_data)
  }

  when(state === s_D_FWB_REQ) {
    when(xact.op === TCTagOp.Create || io.match_fetch) {
      (0 until outerDataBeats).foreach(i => {
        fetch_buf(i) := beat_data_update(UInt(0,outerDataBits), UInt(i))
      })
      meta_tag := addrTag
      meta_state := TCMetadata.Dirty // create always ends up in dirty
      meta_tagFlag := write_tag
      fetch_sent := Bool(true)
      fetch_done := Bool(true)
    }.otherwise{
      meta_tag := addrTag
      meta_state := TCMetadata.Clean
      meta_tagFlag := Bool(false)
    }
  }
  when(state === s_D_FWB_RESP) {
    when(io.tl.acquire.fire()) {
      fetch_sent := Bool(true)
    }
    when(io.tl.grant.valid) {
      val m_update_data = beat_data_update(io.tl.grant.bits.data, fetch_cnt)
      fetch_buf(fetch_cnt) := m_update_data
      meta_tagFlag := meta_tagFlag || m_update_data =/= UInt(0)
      fetch_tagFlag := fetch_tagFlag || io.tl.grant.bits.data =/= UInt(0)
      meta_state := Mux(m_update_data =/= io.tl.grant.bits.data, TCMetadata.Dirty, meta_state)
      fetch_done := fetch_cnt_done
    }
    when(writeback_done && fetch_done) {
      fetch_sent := Bool(false)
      fetch_done := Bool(false)
      fetch_tagFlag := Bool(false)
    }
  }

  when(state === s_D_FWB_REQ && io.match_fetch) {
    printf(s"TagXact$id: (%d) fetch a zero block 0x%x from writeback scoreboard\n", io.xact.resp.bits.id, io.match_addr << tlBlockOffsetBits)
  }

  when(io.xact.resp.valid) {
    when(xact.op === TCTagOp.Read && io.xact.resp.bits.hit) {
      printf(s"TagXact$id: (%d) Read 0x%x => %x\n", io.xact.resp.bits.id, xact.addr, io.xact.resp.bits.data)
    }
    when(xact.op === TCTagOp.Read && !io.xact.resp.bits.hit) {
      printf(s"TagXact$id: (%d) Read 0x%x miss\n", io.xact.resp.bits.id, xact.addr)
    }
    when(xact.op === TCTagOp.FetchRead) {
      printf(s"TagXact$id: (%d) FetchRead 0x%x => %x\n", io.xact.resp.bits.id, xact.addr, io.xact.resp.bits.data)
    }
    when(xact.op === TCTagOp.Write) {
      printf(s"TagXact$id: (%d) Write 0x%x <= %x using mask %x %d\n", io.xact.resp.bits.id, xact.addr, xact.data, xact.mask, io.xact.resp.bits.tagUpdate)
    }
    when(xact.op === TCTagOp.Create) {
      printf(s"TagXact$id: (%d) Create 0x%x <= %x using mask %x %d\n", io.xact.resp.bits.id, xact.addr, xact.data, xact.mask, io.xact.resp.bits.tagUpdate)
    }
  }

  // state machine
  when(state === s_IDLE && xact_queue.fire()) {
    state := s_M_R_REQ
  }
  when(state === s_M_R_REQ && io.meta.read.fire()) {
    state := s_M_R_RESP
  }
  when(state === s_M_R_RESP && io.meta.resp.valid) {
    meta_tag := io.meta.resp.bits.meta.tag
    meta_state := io.meta.resp.bits.meta.state
    meta_tagFlag := io.meta.resp.bits.meta.tagFlag
    when(io.meta.resp.bits.hit) {
      when(!io.meta.resp.bits.meta.tagFlag) {
        when(TCTagOp.isRead(xact.op) || !write_tag) {
          // R/F+R hit,t==0
          io.xact.resp.bits.tagFlag := Bool(false)
          io.xact.resp.valid := Bool(true)
          state := s_IDLE
        }.otherwise{
          // W/C hit,t==0
          io.xact.resp.bits.tagFlag := Bool(true)
          io.xact.resp.bits.tagUpdate := Bool(true)
          io.xact.resp.valid := Bool(true)
          meta_state := TCMetadata.Dirty
          meta_tagFlag := Bool(true)
          state := s_D_RW
        }
      }.otherwise{
        // All hit,t!=0
        state := s_D_R_REQ
      }
    }.otherwise{
      when(xact.op === TCTagOp.Read) {
        // R miss
        io.xact.resp.bits.hit := Bool(false)
        io.xact.resp.valid := Bool(true)
        state := s_IDLE
      }.otherwise{
        // F+R/W/C miss
        state := s_D_FWB_REQ
        when(xact.op === TCTagOp.Create && io.match_create) {
          xact.op := TCTagOp.Write
          printf(s"TagXact$id: (%d) Degrade Create into Write operation for 0x%x\n", io.xact.resp.bits.id, xact.addr)
        }
      }
    }
  }
  when(state === s_D_FWB_REQ) {
    state := s_D_FWB_RESP
  }
  when(state === s_D_FWB_RESP && fetch_done && writeback_done) {
    // F+R/W/C miss
    io.xact.resp.bits.tagUpdate := meta_tagFlag =/= fetch_tagFlag
    io.xact.resp.bits.data := fetch_buf(row)
    io.xact.resp.valid := Bool(true)
    when(TCTagOp.isRead(xact.op)) {
      xact.data := fetch_buf(row) // store read data for final xact.resp
    }
    state := s_D_BW
  }
  when(state === s_D_R_REQ && io.data.read.fire()) {
    state := s_D_R_RESP
  }
  when(state === s_D_R_RESP && io.data.resp.valid) {
    val data_update = (io.data.resp.bits.data & ~xact.mask) | xact.data
    when(TCTagOp.isRead(xact.op) || data_update === io.data.resp.bits.data) {
      // R/F+R hit,t!=0
      io.xact.resp.bits.data := io.data.resp.bits.data
      io.xact.resp.valid := Bool(true)
      state := s_IDLE
    }.otherwise{
      // W/C hit, t!=0
      xact.data := data_update
      meta_state := TCMetadata.Dirty
      meta_tagFlag := data_update.orR
      state := s_D_RW
      when(data_update.orR) { // if t' != 0
        io.xact.resp.bits.tagFlag := Bool(true)
        io.xact.resp.valid := Bool(true)
      }
    }
  }
  when(state === s_D_BW && data_done) {
    state := s_M_W
  }
  when(state === s_D_RW && io.data.write.fire()) {
    when(meta_tagFlag)
    {
      // t' != 0
      state := s_M_W
    }.otherwise{
      // t: 1->0?
      state := s_D_C_REQ
    }
  }
  when(state === s_D_C_REQ && data_done) {
    state := s_D_C_RESP
  }
  when(state === s_D_C_RESP && check_done) {
    io.xact.resp.bits.tagFlag := meta_tagFlag || io.data.resp.bits.data =/= UInt(0)
    io.xact.resp.bits.tagUpdate := !io.xact.resp.bits.tagFlag
    io.xact.resp.valid := Bool(true)
    state := s_M_W
  }
  when(state === s_M_W && io.meta.write.fire()) {
    state := s_IDLE
  }

}

////////////////////////////////////////////////////////////////
// Memory Transaction Tracker

class TCXact(implicit p: Parameters) extends TCBundle()(p) with HasTCAddr {
  val rw = Bool() // r:0 w:1
  val mem_data = UInt(width = rowBits)
  val mem_mask = UInt(width = rowBits)
  val tt_data = UInt(width = rowBits)
  val tm_data = Bool()
  val tc_tagUpdate = Bool()
  val tc_tagFlag = Bool()
}

class TCMemXactTracker(id: Int)(implicit p: Parameters) extends TCModule()(p)
    with HasDataBeatCounters
{
  val io = new Bundle {
    val inner = new ManagerTileLinkIO()(p.alterPartial({case TLId => p(InnerTLId)}))
    val outer = new ClientUncachedTileLinkIO()(p.alterPartial({case TLId => p(OuterTLId)}))
    val tc = new TCTagXactIO
    val tl_block = Bool(OUTPUT)                                    // start to block other tl transactions
    val tl_addr_block = UInt(OUTPUT, width=inner.tlBlockAddrBits)  // for cache line comparison
    val tm0_addr = UInt(OUTPUT, width=inner.tlBlockAddrBits)
    val tt_addr = UInt(OUTPUT, width=inner.tlBlockAddrBits)
    val create_tm0 = Bool(OUTPUT)                                  // active for create a TM0 block
    val create_tt = Bool(OUTPUT)                                   // active for create a TT block
    val create_rdy = Bool(INPUT)
  }
  def inner: ManagerTileLinkIO = io.inner
  def outer: ClientUncachedTileLinkIO = io.outer
  def innerPM : Parameters = p.alterPartial({case TLId =>innerTLId})
  def outerPM : Parameters = p.alterPartial({case TLId =>outerTLId})
  val coh = ManagerMetadata.onReset(innerPM)

  // ------------ tag cache state machine states -------------- //
  // TT.R               read tag table
  // TM0.R              read tag map 0
  // TM1.F+R            force read tag map 1
  // TM0.F+R            force read tag map 0
  // TM0.C              create an empty line in tag map 0
  // TT.F+R             force read tag table
  // TT.C               create a line in tag table
  // TT.W               write to tag table
  // TM0.W              write to tag map 0
  // TM1.W              write to tag map 1

  val ts_IDLE :: ts_TT_R :: ts_TM0_R :: ts_TM1_FR :: ts_TM0_FR :: ts_TT_FR :: ts_TM0_C :: ts_TT_C :: ts_TT_W :: ts_TM0_W :: ts_TM1_W :: Nil = Enum(UInt(), 11)
  val tc_state_next = Wire(init = ts_IDLE)
  val tc_state = Reg(init = ts_IDLE)
  tc_state := tc_state_next
  val tc_xact = Reg(new TCXact)                 // record of tag cache search procedure
  val tc_req_valid = Wire(Bool())               // start the tag cache search transaction
  val tx_req_fire = Reg(init = Bool(false))     // tag transaction request fired
  val tx_resp_hit = Reg(init = Bool(false))     // tag cache search hit
  val tx_resp_valid = Reg(init = Bool(false))   // response from tag transaction trackers ready
  val tc_wdata_valid = Reg(init = Bool(false))  // the tag write data is ready from TileLink
  val tc_tt_update =                            // whether needs to update tag table
    ((tc_xact.tt_data & ~tc_xact.mem_mask) | tc_xact.mem_data) =/= tc_xact.tt_data
  val tc_tt_addr = tgHelper.pa2tta(tc_xact.addr)
  val tc_tt_byte_index = tgHelper.pa2ttr(tc_xact.addr, rowOffBits)
  val tc_tt_valid = Reg(init = Bool(false))     // identify when tc_xact.tt_data is ready
  val tc_tm0_addr = tgHelper.pa2tm0a(tc_xact.addr)
  val tc_tm0_bit_index = tgHelper.pa2tm0b(tc_xact.addr, rowOffBits)
  val tc_tm1_addr = tgHelper.pa2tm1a(tc_xact.addr)
  val tc_tm1_bit_index = tgHelper.pa2tm1b(tc_xact.addr, rowOffBits)

  when(tc_state =/= tc_state_next) {
    tx_req_fire := Bool(false)
    tx_resp_valid := Bool(false)
  }
  when(io.tc.req.fire()) {
    tx_req_fire := Bool(true)
  }
  when(io.tc.resp.valid) {
    tx_resp_hit := io.tc.resp.bits.hit
    tx_resp_valid := Bool(true)
  }

  when(!tx_req_fire && tc_state =/= ts_IDLE && !io.create_rdy) {
    printf(s"MemTransactor$id Waits for create scoreboard ready...\n")
  }

  // tag transaction request
  io.tc.req.valid := !tx_req_fire && tc_state =/= ts_IDLE && io.create_rdy
  io.tc.req.bits.id   := UInt(id)
  io.tc.req.bits.data := UInt(0)
  io.tc.req.bits.mask := UInt(0)
  io.tc.req.bits.addr := UInt(0)
  io.tc.req.bits.op   := UInt(0)
  switch(tc_state) {
    is(ts_TT_R) {
      io.tc.req.bits.addr := tc_tt_addr
      io.tc.req.bits.op   := TCTagOp.Read
    }
    is(ts_TM0_R) {
      io.tc.req.bits.addr := tc_tm0_addr
      io.tc.req.bits.op   := TCTagOp.Read
    }
    is(ts_TM1_FR) {
      io.tc.req.bits.addr := tc_tm1_addr
      io.tc.req.bits.op   := TCTagOp.FetchRead
    }
    is(ts_TM0_FR) {
      io.tc.req.bits.addr := tc_tm0_addr
      io.tc.req.bits.op   := TCTagOp.FetchRead
    }
    is(ts_TT_FR) {
      io.tc.req.bits.addr := tc_tt_addr
      io.tc.req.bits.op   := TCTagOp.FetchRead
    }
    is(ts_TM0_C) {
      io.tc.req.bits.addr := tc_tm0_addr
      io.tc.req.bits.op   := TCTagOp.Create
    }
    is(ts_TT_C) {
      io.tc.req.bits.data := tc_xact.mem_data
      io.tc.req.bits.mask := tc_xact.mem_mask
      io.tc.req.bits.addr := tc_tt_addr
      io.tc.req.bits.op   := TCTagOp.Create
    }
    is(ts_TT_W) {
      io.tc.req.bits.data := tc_xact.mem_data
      io.tc.req.bits.mask := tc_xact.mem_mask
      io.tc.req.bits.addr := tc_tt_addr
      io.tc.req.bits.op   := TCTagOp.Write
    }
    is(ts_TM0_W) {
      io.tc.req.bits.data := tc_xact.tc_tagFlag << tc_tm0_bit_index
      io.tc.req.bits.mask := UInt(1) << tc_tm0_bit_index
      io.tc.req.bits.addr := tc_tm0_addr
      io.tc.req.bits.op   := TCTagOp.Write
    }
    is(ts_TM1_W) {
      io.tc.req.bits.data := tc_xact.tc_tagFlag << tc_tm1_bit_index
      io.tc.req.bits.mask := UInt(1) << tc_tm1_bit_index
      io.tc.req.bits.addr := tc_tm1_addr
      io.tc.req.bits.op   := TCTagOp.Write
    }
  }

  // tag transaction response
  when(io.tc.resp.valid) {
    switch(tc_state) {
      is(ts_TT_R) {
        tc_xact.tt_data := io.tc.resp.bits.data
      }
      is(ts_TM0_R) {
        tc_xact.tm_data := io.tc.resp.bits.data(tc_tm0_bit_index)
      }
      is(ts_TM1_FR) {
        tc_xact.tm_data := io.tc.resp.bits.data(tc_tm1_bit_index)
      }
      is(ts_TM0_FR) {
        tc_xact.tm_data := io.tc.resp.bits.data(tc_tm0_bit_index)
      }
      is(ts_TT_FR) {
        tc_xact.tt_data := io.tc.resp.bits.data
      }
      is(ts_TM0_C) {
        tc_xact.tc_tagFlag := io.tc.resp.bits.tagFlag
      }
      is(ts_TT_C) {
        tc_xact.tc_tagFlag := io.tc.resp.bits.tagFlag
      }
      is(ts_TT_W) {
        tc_xact.tc_tagFlag := io.tc.resp.bits.tagFlag
        tc_xact.tc_tagUpdate := io.tc.resp.bits.tagUpdate
      }
      is(ts_TM0_W) {
        tc_xact.tc_tagFlag := io.tc.resp.bits.tagFlag
        tc_xact.tc_tagUpdate := io.tc.resp.bits.tagUpdate
      }
    }
  }

  // block memory side transactions
  io.tl_block := tc_state =/= ts_IDLE

  // scoreboard
  io.tm0_addr := tc_tm0_addr >> blockOffBits
  io.tt_addr := tc_tt_addr >> blockOffBits
  io.create_tm0 := tc_state === ts_TM0_C
  io.create_tt := tc_state === ts_TT_C

  // -------------- the shared state machine ----------------- //
  tc_state_next := tc_state
  when(tc_state === ts_IDLE && tc_req_valid) {
    tc_tt_valid := Bool(false)
    when(Bool(true)) {          // most tagged
      tc_state_next := ts_TT_R
    }.otherwise{                // most untagged
      tc_state_next := ts_TM1_FR
    }
  }
  when(tx_resp_valid) {
    when(tc_state === ts_TT_R) {
      when(tx_resp_hit) {
        tc_tt_valid := Bool(true)
        when(!tc_xact.rw) { // MEM.R TT.hit
          tc_state_next := ts_IDLE
        }.elsewhen(tc_wdata_valid) { // MEM.W TT.hit
          tc_state_next := Mux(tc_tt_update, ts_TT_W, ts_IDLE)
        }
      }.otherwise{ // TT.miss
        tc_xact.tt_data := UInt(0) // assuming TT=0
        tc_state_next := ts_TM0_R
      }
    }
    when(tc_state === ts_TM0_R) {
      when(tx_resp_hit) {
        tc_tt_valid := !tc_xact.tm_data
        when(tc_xact.tm_data) { // TM0.hit TM0=1
          tc_state_next := ts_TT_FR
        }.elsewhen(!tc_xact.rw) { // MEM.R TM0.hit TM0=0
          tc_state_next := ts_IDLE
        }.elsewhen(tc_wdata_valid) { // MEM.W TM0.hit TM0=0
          tc_state_next := Mux(tc_tt_update, ts_TT_C, ts_IDLE)
        }
      }.otherwise{ // TM0.miss
        tc_state_next := ts_TM1_FR
      }
    }
    when(tc_state === ts_TM1_FR) {
      tc_tt_valid := !tc_xact.tm_data
      when(tc_xact.tm_data) { // TM1=1
        tc_state_next := ts_TM0_FR
      }.elsewhen(!tc_xact.rw) { // MEM.R TM1=0
        tc_state_next := ts_IDLE
      }.elsewhen(tc_wdata_valid) { // MEM.W TM1=0
        tc_state_next := Mux(tc_tt_update, ts_TM0_C, ts_IDLE)
      }
    }
    when(tc_state === ts_TM0_FR) {
      tc_tt_valid := !tc_xact.tm_data
      when(tc_xact.tm_data) { // TM0=1
        tc_state_next := ts_TT_FR
      }.elsewhen(!tc_xact.rw) { // MEM.R TM0=0
        tc_state_next := ts_IDLE
      }.elsewhen(tc_wdata_valid) { // MEM.W TM0=0
        tc_state_next := Mux(tc_tt_update, ts_TT_C, ts_IDLE)
      }
    }
    when(tc_state === ts_TM0_C) {
      tc_state_next := ts_TT_C
    }
    when(tc_state === ts_TT_FR) {
      tc_tt_valid := Bool(true)
      when(!tc_xact.rw) { // MEM.R
        tc_state_next := ts_IDLE
      }.elsewhen(tc_wdata_valid) { // MEM.W
        tc_state_next := Mux(tc_tt_update, ts_TT_W, ts_IDLE)
      }
    }
    when(tc_state === ts_TT_C) {
      tc_state_next := ts_TM0_W
    }
    when(tc_state === ts_TT_W) {
      tc_state_next := Mux(tc_xact.tc_tagUpdate, ts_TM0_W, ts_IDLE)
    }
    when(tc_state === ts_TM0_W) {
      tc_state_next := Mux(tc_xact.tc_tagUpdate, ts_TM1_W,ts_IDLE)
    }
    when(tc_state === ts_TM1_W) {
      tc_state_next := ts_IDLE
    }
  }
}

class TCMemReleaseTracker(id: Int)(implicit p: Parameters) extends TCMemXactTracker(id)(p) {

  // ------------ tag cache state machine states -------------- //
  val ms_IDLE :: ms_IREL :: ms_OACQ :: ms_IGNT :: Nil = Enum(UInt(), 4)
  val mt_state = Reg(init = ms_IDLE)

  require(inner.tlDataBits == outer.tlDataBits)
  require(inner.tlDataBeats == outer.tlDataBeats)
  val xact = Reg(new BufferedReleaseFromSrc()(innerPM))

  val irel_done = connectIncomingDataBeatCounter(inner.release)
  val (oacq_cnt, oacq_done) = connectOutgoingDataBeatCounter(outer.acquire)

  // inner release
  when(inner.release.fire()) {
    xact.data_buffer(inner.release.bits.addr_beat) := inner.release.bits.data
    xact.tag_buffer(inner.release.bits.addr_beat) := inner.release.bits.tag
  }
  inner.release.ready := (mt_state === ms_IDLE && tc_state === ts_IDLE) || mt_state === ms_IREL

  // output acquire
  outer.acquire.valid := mt_state === ms_OACQ
  outer.acquire.bits := PutBlock(
    client_xact_id = UInt(id),
    addr_block = xact.addr_block,
    addr_beat = oacq_cnt,
    data = xact.data_buffer(oacq_cnt)
  )(outerPM)

  // input grant
  inner.grant.valid := mt_state === ms_IGNT && outer.grant.valid
  inner.grant.bits := coh.makeGrant(xact)
  outer.grant.ready := mt_state === ms_IGNT && inner.grant.ready

  // tag
  tc_req_valid := mt_state === ms_IDLE && inner.release.fire()

  when(mt_state === ms_IDLE && inner.release.fire()) {
    tc_wdata_valid := Bool(false)
    tc_xact.rw := Bool(true)
    tc_xact.addr := inner.release.bits.full_addr()
  }

  when(mt_state === ms_OACQ && !tc_wdata_valid) {
    tc_xact.mem_data := xact.tag_buffer.toBits << (tc_tt_byte_index * UInt(8))
    tc_xact.mem_mask := ~UInt(0,tgHelper.cacheBlockTagBits) << (tc_tt_byte_index * UInt(8))
    tc_wdata_valid := Bool(true)
  }

  // tl conflicts
  io.tl_addr_block := xact.addr_block

  // ------------ memory tracker state machine ---------------- //
  when(mt_state === ms_IDLE && inner.release.fire()) {
    xact := inner.release.bits
    mt_state := Mux(irel_done, ms_OACQ, ms_IREL)
  }
  when(mt_state === ms_IREL && irel_done) {
    mt_state := ms_OACQ
  }
  when(mt_state === ms_OACQ && oacq_done) {
    mt_state := Mux(xact.requiresAck(), ms_IGNT, ms_IDLE)
  }
  when(mt_state === ms_IGNT && inner.grant.fire()) {
    mt_state := ms_IDLE
  }

}

class TCMemAcquireTracker(id: Int)(implicit p: Parameters) extends TCMemXactTracker(id)(p) {

  // ------------ tag cache state machine states -------------- //
  val ms_IDLE :: ms_IACQ :: ms_OACQ :: ms_OGNT :: ms_IGNT :: ms_IFIN :: Nil = Enum(UInt(), 6)
  val mt_state = Reg(init = ms_IDLE)

  require(inner.tlDataBits == outer.tlDataBits)
  require(inner.tlDataBeats == outer.tlDataBeats)
  val xact  = Reg(new BufferedAcquireFromSrc()(innerPM))

  val iacq_cnt = inner.acquire.bits.addr_beat
  val iacq_cnt_reg = RegEnable(iacq_cnt, inner.acquire.fire())
  val iacq_done = connectIncomingDataBeatCounter(inner.acquire)
  val ognt_cnt = outer.grant.bits.addr_beat
  val ognt_cnt_reg = RegEnable(ognt_cnt, outer.grant.fire())
  val ognt_done = connectIncomingDataBeatCounter(outer.grant)
  val (ignt_cnt, ignt_done) = connectOutgoingDataBeatCounter(inner.grant, ognt_cnt_reg)
  val (oacq_cnt, oacq_done) = connectOutgoingDataBeatCounter(outer.acquire, iacq_cnt_reg)

  // inner acquire
  when(mt_state === ms_IDLE) {
    (0 until refillCycles).foreach( i => {
      xact.wmask_buffer(i) := UInt(0)
      xact.tmask_buffer(i) := UInt(0)
      xact.tag_buffer(i) := UInt(0)    // always assume mem_data is initially 0 when checking tag update
    })
  }

  when(inner.acquire.fire()) {
    xact.data_buffer(iacq_cnt) := inner.acquire.bits.data
    xact.wmask_buffer(iacq_cnt) := inner.acquire.bits.wmask()
    xact.tag_buffer(iacq_cnt) := inner.acquire.bits.tag
    xact.tmask_buffer(iacq_cnt) := inner.acquire.bits.tmask()
  }
  inner.acquire.ready := (mt_state === ms_IDLE && tc_state === ts_IDLE) || mt_state === ms_IACQ

  // outer acquire
  outer.acquire.valid := mt_state === ms_OACQ
  outer.acquire.bits := Acquire(
    is_builtin_type = Bool(true),
    a_type = Mux(xact.isBuiltInType(), xact.a_type, Acquire.getBlockType),
    client_xact_id = UInt(id),
    addr_block = xact.addr_block,
    addr_beat = oacq_cnt,
    data = xact.data_buffer(oacq_cnt),
    tag = xact.tag_buffer(oacq_cnt),
    union = Mux(xact.isBuiltInType(),
      Acquire.makeUnion( // re-assemble union to rectify wmask
        a_type = xact.a_type,
        addr_byte = xact.addr_byte(),
        operand_size = xact.op_size(),
        opcode = xact.op_code(),
        wmask = xact.wmask_buffer(oacq_cnt),
        tmask = xact.tmask_buffer(oacq_cnt),
        alloc = Bool(true)
      ),
      Cat(MT_Q, M_XRD, Bool(true))) // coherent Acquire must be getBlock?
  )(outerPM)

  // outer grant
  outer.grant.ready := mt_state === ms_OGNT
  when(outer.grant.fire()) {
    xact.data_buffer(ognt_cnt) := outer.grant.bits.data
  }

  // inner grant
  inner.grant.valid := mt_state === ms_IGNT && (!inner.grant.bits.hasData() || tc_tt_valid)
  val ignt_block_tag = tc_xact.tt_data >> (tc_tt_byte_index * UInt(8))
  val ignt_tag = Wire(Vec(innerDataBeats, UInt(width=innerTagBits)))
  ignt_tag := ignt_tag.fromBits(ignt_block_tag(tgHelper.cacheBlockTagBits-1,0))
  inner.grant.bits := coh.makeGrant(
    acq = xact,
    manager_xact_id = UInt(id),
    addr_beat = ignt_cnt,
    data = xact.data_buffer(ignt_cnt),
    tag = ignt_tag(ignt_cnt)
  )

  // inner finish
  inner.finish.ready := mt_state === ms_IFIN

  // tag
  tc_req_valid := mt_state === ms_IDLE && inner.acquire.fire()

  when(mt_state === ms_IDLE && inner.acquire.fire()) {
    tc_wdata_valid := Bool(false)
    tc_xact.rw := inner.acquire.bits.hasData()
    tc_xact.addr := inner.acquire.bits.full_addr()
  }

  when(mt_state === ms_OACQ && !tc_wdata_valid) {
    tc_xact.mem_data := xact.tag_buffer.toBits << (tc_tt_byte_index * UInt(8))
    tc_xact.mem_mask := FillInterleaved(tgBits, xact.tmask_buffer.toBits) << (tc_tt_byte_index * UInt(8))
    tc_wdata_valid := Bool(true)
  }

  // tl conflicts
  io.tl_addr_block := xact.addr_block

  // ------------ memory tracker state machine ---------------- //
  when(mt_state === ms_IDLE && inner.acquire.fire()) {
    xact := inner.acquire.bits
    mt_state := Mux(iacq_done, ms_OACQ, ms_IACQ)
  }
  when(mt_state === ms_IACQ && iacq_done) {
    mt_state := ms_OACQ
  }
  when(mt_state === ms_OACQ && oacq_done) {
    mt_state := ms_OGNT
  }
  when(mt_state === ms_OGNT && ognt_done) {
    mt_state := ms_IGNT
  }
  when(mt_state === ms_IGNT && ignt_done) {
    if(uncached) mt_state := ms_IDLE
    else mt_state := Mux(inner.grant.bits.requiresAck(), ms_IFIN, ms_IDLE)
  }
  when(mt_state === ms_IFIN && inner.finish.valid) {
    mt_state := ms_IDLE
  }
}

// initialize the top map after power up
class TCInitiator(id:Int)(implicit p: Parameters) extends TCModule()(p) {
  val io = new Bundle {
    val mem_xact = new TCTagXactIO().flip // tag transaction from memTrackers
    val tag_xact = new TCTagXactIO        // tag transaction to tagTrackers
  }

  require(isPow2(nTagTransactors))
  val xBlockSize = p(CacheBlockBytes) * nTagTransactors
  val nBlocks = tgHelper.topSize / xBlockSize

  val rst_req_cnt = Reg(init = UInt(0, log2Up(nBlocks+1)))
  val rst_ack_cnt = Reg(init = UInt(0, log2Up(nBlocks+1)))
  when(rst_req_cnt =/= UInt(nBlocks) && io.tag_xact.req.fire()) { rst_req_cnt := rst_req_cnt + UInt(1) }
  when(rst_ack_cnt =/= UInt(nBlocks) && io.tag_xact.resp.valid) { rst_ack_cnt := rst_ack_cnt + UInt(1) }

  // normal operation
  io.tag_xact.req.valid  := io.mem_xact.req.valid
  io.tag_xact.req.bits   := io.mem_xact.req.bits
  io.mem_xact.req.ready  := io.tag_xact.req.ready
  io.mem_xact.resp.valid := io.tag_xact.resp.valid
  io.mem_xact.resp.bits  := io.tag_xact.resp.bits

  when(rst_ack_cnt =/= UInt(nBlocks)) {
    // during initialization
    io.tag_xact.req.valid  := rst_req_cnt =/= UInt(nBlocks)
      io.tag_xact.req.bits.data := UInt(0)
      io.tag_xact.req.bits.mask := UInt(0)
      io.tag_xact.req.bits.addr :=
        UInt(tgHelper.map1Base) + UInt(id) * UInt(p(CacheBlockBytes)) + rst_req_cnt * UInt(xBlockSize)
      io.tag_xact.req.bits.op   := TCTagOp.Create
    io.mem_xact.req.ready  := Bool(false)
    io.mem_xact.resp.valid := Bool(false)
  }

}

class TCTagXactDemux(banks: Int)(implicit p: Parameters) extends TCModule()(p) {
  val io = new Bundle {
    val in = new TCTagXactIO().flip
    val out = Vec(banks, new TCTagXactIO)
  }

  require(isPow2(banks)) // tagXactTracker is banked

  val idx = io.in.req.bits.addr >> blockOffBits
  val i_sel = Wire(Vec(banks,Bool()))
  if(banks == 1) {
    i_sel(0) := Bool(true)
  } else {
    val index = if(banks>2) idx(log2Up(banks)-1,0) else idx(0)
    i_sel := i_sel.fromBits(UIntToOH(index,banks))
  }
  val o_sel = io.out.map(_.resp.valid)

  io.out.zip(i_sel).foreach{ case(o, s) => {
    o.req.valid := io.in.req.valid && s
    o.req.bits := io.in.req.bits
  }}

  io.in.req.ready := Mux1H(i_sel, io.out.map(_.req.ready))
  io.in.resp := Mux1H(o_sel, io.out.map(_.resp))
}

////////////////////////////////////////////////////////////////
// Top level of the Tag Cache

class TagCache(implicit p: Parameters) extends TCModule()(p)
  with HasCoherenceAgentWiringHelpers
{
  val io = new ManagerTLIO

  val meta      = Module(new TCMetadataArray)
  val data      = Module(new TCDataArray)

  val relTrackers =
    (0                      until nMemReleaseTransactors).map(id => Module(new TCMemReleaseTracker(id)))
  val acqTrackers =
    (nMemReleaseTransactors until nMemAcquireTransactors).map(id => Module(new TCMemAcquireTracker(id)))
  val memTrackers = relTrackers ++ acqTrackers

  val acq_size = acqTrackers.size

  // banked tag trackers
  val tagTrackerInitiators = (0 until nTagTransactors).map( id =>
    Module(new TCInitiator(id)))

  val tagTrackers = (nMemTransactors until nMemTransactors + nTagTransactors).map(id =>
    Module(new TCTagXactTracker(id)))

  val wb = Module(new TCWritebackUnit(nMemTransactors + nTagTransactors))

  // connect TileLink outputs
  val outer_arb = Module(new ClientUncachedTileLinkIOArbiter(nMemTransactors + nTagTransactors + 1)
                    (p.alterPartial({ case TLId => p(OuterTLId) })))
  outer_arb.io.in <> memTrackers.map(_.outer) ++ tagTrackers.map(_.io.tl) :+ wb.io.tl
  io.outer <> outer_arb.io.out

  // connect TileLink inputs
  val acq_rel_conflict =  if(uncached) Bool(false) else {
    // when acq and rel for the same block, rel first
    io.inner.acquire.valid &&
    io.inner.release.valid &&
    io.inner.acquire.bits.addr_block === io.inner.release.bits.addr_block
  }

  val tl_acq_block = Vec(memTrackers.map(t=>{t.io.tl_block && t.io.tl_addr_block === io.inner.acquire.bits.addr_block})).toBits.orR
  val tl_acq_match = Vec(acqTrackers.map(t=>{t.io.tl_block && t.inner.acquire.ready && t.io.tl_addr_block === io.inner.acquire.bits.addr_block})).toBits
  val tl_acq_alloc = Mux(tl_acq_match.orR, tl_acq_match, Mux(tl_acq_block, UInt(0,nMemAcquireTransactors),
    PriorityEncoderOH(Vec(acqTrackers.map(_.inner.acquire.ready)).toBits)))

  acqTrackers.zipWithIndex.foreach{ case(t,i) => {
    t.inner.acquire.valid := io.inner.acquire.valid && tl_acq_alloc(i) && !acq_rel_conflict
    t.inner.acquire.bits := io.inner.acquire.bits
  }}
  io.inner.acquire.ready := tl_acq_alloc.orR

  if(!uncached) {
    val tl_rel_block = Vec(memTrackers.map(t=>{t.io.tl_block && t.io.tl_addr_block === io.inner.release.bits.addr_block})).toBits.orR
    val tl_rel_match = Vec(relTrackers.map(t=>{t.io.tl_block && t.inner.release.ready && t.io.tl_addr_block === io.inner.release.bits.addr_block})).toBits
    val tl_rel_alloc = Mux(tl_rel_match.orR, tl_rel_match, Mux(tl_rel_block, UInt(0,nMemReleaseTransactors),
      PriorityEncoderOH(Vec(relTrackers.map(_.inner.release.ready)).toBits)))

    relTrackers.zipWithIndex.foreach{ case(t,i) => {
      t.inner.release.valid := io.inner.release.valid && tl_rel_alloc(i)
      t.inner.release.bits := io.inner.release.bits
    }}
    io.inner.release.ready := tl_rel_alloc.orR
  }

  doOutputArbitration(io.inner.grant, memTrackers.map(_.inner.grant))
  doInputRouting(io.inner.finish, memTrackers.map(_.inner.finish))

  // helpers for internal connection
  def doTcOutputArbitration[T <: Bundle](out: DecoupledIO[T], ins: Seq[DecoupledIO[T]])
  {
    val arb = Module(new RRArbiter(out.bits, ins.size))
    out <> arb.io.out
    arb.io.in <> ins
  }

  def doTcInputRouting[T <: Bundle with HasTCId](in: ValidIO[T], outs: Seq[ValidIO[T]], base:Int = 0) {
    outs.zipWithIndex.foreach{ case(out, i) => {
      out.bits := in.bits
      out.valid := in.valid && in.bits.id === UInt(base+i)
    }}
  }

  // connect transactions from memTrackers to TagTrackers
  val tagXactDemuxers = memTrackers.map(_ => Module(new TCTagXactDemux(nTagTransactors)))
  memTrackers.zip(tagXactDemuxers).foreach{ case(t, m) => t.io.tc <> m.io.in}

  tagTrackers.zip(tagTrackerInitiators).zipWithIndex.foreach{ case((t, ti), i) => {
    t.io.xact <> ti.io.tag_xact
    doTcOutputArbitration(ti.io.mem_xact.req, tagXactDemuxers.map(_.io.out(i).req))
    doTcInputRouting(ti.io.mem_xact.resp, tagXactDemuxers.map(_.io.out(i).resp))
  }}

  // connect tagXactTrackers to meta array
  doTcOutputArbitration(meta.io.read, tagTrackers.map(_.io.meta.read))
  doTcOutputArbitration(meta.io.write, tagTrackers.map(_.io.meta.write))
  doTcInputRouting(meta.io.resp, tagTrackers.map(_.io.meta.resp), nMemTransactors)

  // connect tagXactTrackers and writeback unit to data array
  doTcOutputArbitration(data.io.read, tagTrackers.map(_.io.data.read) :+ wb.io.data.read)
  doTcOutputArbitration(data.io.write, tagTrackers.map(_.io.data.write) :+ wb.io.data.write)
  doTcInputRouting(data.io.resp, tagTrackers.map(_.io.data.resp) :+ wb.io.data.resp, nMemTransactors)

  // connect tagXactTrackers to writeback unit
  doTcOutputArbitration(wb.io.xact.req, tagTrackers.map(_.io.wb.req))
  doTcInputRouting(wb.io.xact.resp, tagTrackers.map(_.io.wb.resp), nMemTransactors)

  // empty block scoreboard
  tagTrackers.zipWithIndex.foreach{ case(t,i) => {
    wb.io.addr_block(i) := t.io.match_addr
    t.io.match_fetch <> wb.io.addr_match(i)
  }}
  wb.io.mtActive := Vec(memTrackers.map(t =>
    t.io.tl_block && (t.io.tm0_addr === wb.io.wb_addr || t.io.tt_addr === wb.io.wb_addr))).toBits

  // create scoreboard
  val create_sb_tm0_addr = Reg(init=Vec.fill(nMemTransactors)(UInt(0,io.inner.tlBlockAddrBits)))
  val create_sb_tm0_stat = Reg(init=Vec.fill(nMemTransactors)(UInt(0,nMemTransactors)))
  val create_sb_tt_addr = Reg(init=Vec.fill(nMemTransactors)(UInt(0,io.inner.tlBlockAddrBits)))
  val create_sb_tt_stat = Reg(init=Vec.fill(nMemTransactors)(UInt(0,nMemTransactors)))

  tagTrackers.foreach( t => {
    t.io.match_create := (0 until nMemTransactors).map( i =>
      (t.io.match_addr === create_sb_tm0_addr(i) && create_sb_tm0_stat(i) =/= UInt(0)) ||
      (t.io.match_addr === create_sb_tt_addr(i) && create_sb_tt_stat(i) =/= UInt(0))
    ).reduce(_||_)
  })

  (0 until nMemTransactors).foreach( i => {
    when(memTrackers(i).io.tl_block && create_sb_tm0_stat(i)(i)) {
      create_sb_tm0_stat(i) := Vec(memTrackers.zipWithIndex.map{ case(t,j) =>
        if(i==j) Bool(true) else t.io.tl_block && t.io.tm0_addr === create_sb_tm0_addr(i)}).toBits
    }.elsewhen(create_sb_tm0_stat(i) =/= UInt(0)) {
      create_sb_tm0_stat(i) := create_sb_tm0_stat(i) & Vec(memTrackers.map(_.io.tl_block)).toBits
    }.otherwise{
      tagTrackers.foreach( t => {
        when(t.io.create && memTrackers(i).io.create_tm0 && t.io.xact.resp.bits.id === UInt(i)) {
          create_sb_tm0_addr(i) := t.io.match_addr
          create_sb_tm0_stat(i) := UInt(1) << i
        }
      })
    }

    when(memTrackers(i).io.tl_block && create_sb_tt_stat(i)(i)) {
      create_sb_tt_stat(i) := Vec(memTrackers.zipWithIndex.map{ case(t,j) =>
        if(i==j) Bool(true) else t.io.tl_block && t.io.tm0_addr === create_sb_tt_addr(i)}).toBits
    }.elsewhen(create_sb_tt_stat(i) =/= UInt(0)) {
      create_sb_tt_stat(i) := create_sb_tt_stat(i) & Vec(memTrackers.map(_.io.tl_block)).toBits
    }.otherwise {
      tagTrackers.foreach( t => {
        when(t.io.create && memTrackers(i).io.create_tt && t.io.xact.resp.bits.id === UInt(i)) {
          create_sb_tt_addr(i) := t.io.match_addr
          create_sb_tt_stat(i) := UInt(1) << i
        }
      })
    }

    memTrackers(i).io.create_rdy := (memTrackers(i).io.create_tm0 && create_sb_tm0_stat(i) === UInt(0)) ||
                                    (memTrackers(i).io.create_tt && create_sb_tt_stat(i) === UInt(0)) ||
                                    (!memTrackers(i).io.create_tm0 && !memTrackers(i).io.create_tt)
  })

}
