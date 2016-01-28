// See LICENSE for license details.

package uncore

import Chisel._
import scala.math._

class Unsigned(x: Int) {
  require(x >= 0)
  def clog2: Int = { require(x > 0); ceil(log(x)/log(2)).toInt }
  def log2: Int = { require(x > 0); floor(log(x)/log(2)).toInt }
  def isPow2: Boolean = x > 0 && (x & (x-1)) == 0
  def nextPow2: Int = if (x == 0) 1 else 1 << clog2
}

object MuxBundle {
  def apply[T <: Data] (default: T, mapping: Seq[(Bool, T)]): T = {
    mapping.reverse.foldLeft(default)((b, a) => Mux(a._1, a._2, b))
  }

  def apply[T <: Data] (key: UInt, default: T, mapping: Seq[(UInt, T)]): T = {
    apply(default, mapping.map{ case (a, b) => (a === key, b) })
  }
}

// Produces 0-width value when counting to 1
class ZCounter(val n: Int) {
  val value = Reg(init=UInt(0, log2Ceil(n)))
  def inc(): Bool = {
    if (n == 1) Bool(true)
    else {
      val wrap = value === UInt(n-1)
      value := Mux(Bool(!isPow2(n)) && wrap, UInt(0), value + UInt(1))
      wrap
    }
  }
}

object ZCounter {
  def apply(n: Int) = new ZCounter(n)
  def apply(cond: Bool, n: Int): (UInt, Bool) = {
    val c = new ZCounter(n)
    var wrap: Bool = null
    when (cond) { wrap = c.inc() }
    (c.value, cond && wrap)
  }
}

object TwoWayCounter {
  def apply(up: Bool, down: Bool, max: Int): UInt = {
    val cnt = Reg(init = UInt(0, log2Up(max+1)))
    when (up && !down) { cnt := cnt + UInt(1) }
    when (down && !up) { cnt := cnt - UInt(1) }
    cnt
  }
}

class FlowThroughSerializer[T <: Bundle with HasTileLinkData](gen: T, n: Int) extends Module {
  val io = new Bundle {
    val in = Decoupled(gen).flip
    val out = Decoupled(gen)
    val cnt = UInt(OUTPUT, log2Up(n))
    val done = Bool(OUTPUT)
  }
  val narrowWidth = io.in.bits.data.getWidth / n
  require(io.in.bits.data.getWidth % narrowWidth == 0)

  if(n == 1) {
    io.out <> io.in
    io.cnt := UInt(0)
    io.done := Bool(true)
  } else {
    val cnt = Reg(init=UInt(0, width = log2Up(n)))
    val wrap = cnt === UInt(n-1)
    val rbits = Reg{io.in.bits}
    val active = Reg(init=Bool(false))

    val shifter = Vec(n, Bits(width = narrowWidth))
    (0 until n).foreach { 
      i => shifter(i) := rbits.data((i+1)*narrowWidth-1,i*narrowWidth)
    }

    io.done := Bool(false)
    io.cnt := cnt
    io.in.ready := !active
    io.out.valid := active || io.in.valid
    io.out.bits := io.in.bits
    when(!active && io.in.valid) {
      when(io.in.bits.hasData()) {
        cnt := Mux(io.out.ready, UInt(1), UInt(0))
        rbits := io.in.bits
        active := Bool(true)
      }
      io.done := !io.in.bits.hasData()
    }
    when(active) {
      io.out.bits := rbits
      io.out.bits.data := shifter(cnt)
      when(io.out.ready) { 
        cnt := cnt + UInt(1)
        when(wrap) {
          cnt := UInt(0)
          io.done := Bool(true)
          active := Bool(false)
        }
      }
    }
  }
}

object FlowThroughSerializer {
  def apply[T <: Bundle with HasTileLinkData](in: DecoupledIO[T], n: Int): DecoupledIO[T] = {
    val fs = Module(new FlowThroughSerializer(in.bits, n))
    fs.io.in.valid := in.valid
    fs.io.in.bits := in.bits
    in.ready := fs.io.in.ready
    fs.io.out
  }
} 

class DecoupledPipe[T <: Data] (gen: T) extends Module {
  val io = new Bundle {
    val pi = Decoupled(gen.clone).flip
    val po = Decoupled(gen.clone)
  }

  val valid = Reg(init=Bool(false))
  val bits = Reg(gen.clone)

  io.pi.ready := !valid || io.po.ready
  io.po.valid := valid
  io.po.bits := bits

  when(io.pi.fire()) {
    valid := Bool(true)
    bits := io.pi.bits
  } .elsewhen(io.po.fire()) {
    valid := Bool(false)
  }
}

class ReorderQueueWrite[T <: Data](dType: T, tagWidth: Int) extends Bundle {
  val data = dType.cloneType
  val tag = UInt(width = tagWidth)

  override def cloneType =
    new ReorderQueueWrite(dType, tagWidth).asInstanceOf[this.type]
}

class ReorderQueue[T <: Data](dType: T, tagWidth: Int, size: Int)
    extends Module {
  val io = new Bundle {
    val enq = Decoupled(new ReorderQueueWrite(dType, tagWidth)).flip
    val deq = new Bundle {
      val valid = Bool(INPUT)
      val tag = UInt(INPUT, tagWidth)
      val data = dType.cloneType.asOutput
      val matches = Bool(OUTPUT)
    }
  }

  val roq_data = Reg(Vec(size, dType.cloneType))
  val roq_tags = Reg(Vec(size, UInt(width = tagWidth)))
  val roq_free = Reg(init = Vec.fill(size)(Bool(true)))

  val roq_enq_addr = PriorityEncoder(roq_free)
  val roq_matches = roq_tags.zip(roq_free)
    .map { case (tag, free) => tag === io.deq.tag && !free }
  val roq_deq_addr = PriorityEncoder(roq_matches)

  io.enq.ready := roq_free.reduce(_ || _)
  io.deq.data := roq_data(roq_deq_addr)
  io.deq.matches := roq_matches.reduce(_ || _)

  when (io.enq.valid && io.enq.ready) {
    roq_data(roq_enq_addr) := io.enq.bits.data
    roq_tags(roq_enq_addr) := io.enq.bits.tag
    roq_free(roq_enq_addr) := Bool(false)
  }

  when (io.deq.valid) {
    roq_free(roq_deq_addr) := Bool(true)
  }
}

object DecoupledHelper {
  def apply(rvs: Bool*) = new DecoupledHelper(rvs)
}

class DecoupledHelper(val rvs: Seq[Bool]) {
  def fire(exclude: Bool, includes: Bool*) = {
    (rvs.filter(_ ne exclude) ++ includes).reduce(_ && _)
  }
}
