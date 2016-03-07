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

class FlowThroughSerializer[T <: HasTileLinkData](gen: T, n: Int) extends Module {
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

    val shifter = Vec(Bits(width = narrowWidth), n)
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
  def apply[T <: HasTileLinkData](in: DecoupledIO[T], n: Int): DecoupledIO[T] = {
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

class SerDesBuffer[T <: Data](gen: T, size: Int, ipw: Int, opw: Int) extends Module {
  val pointerWidth = log2Up(size) + 1
  val io = new Bundle {
    val in = Decoupled(Vec(ipw, gen)).flip
    val in_size = UInt(INPUT, width=log2Up(ipw+1))
    val out = Decoupled(Vec(opw, gen))
    val out_size = UInt(INPUT, width=log2Up(opw+1))
    val count = UInt(OUTPUT, width=pointerWidth)
  }

  val wp = Reg(init = UInt(0, width=pointerWidth))
  val buffer = Reg(Vec(size, gen))
  val wp_r = Wire(UInt(width=pointerWidth))
  val wp_w = Wire(UInt(width=pointerWidth))
  val buffer_r = Wire(Vec(size, gen))
  val buffer_w = Wire(Vec(size, gen))

  wp_w := wp
  buffer_w := buffer
  when(io.in.fire()) {
    wp_w := wp + io.in_size
    for(i <- 0 until ipw) {
      buffer_w(wp + UInt(i)) := io.in.bits(i)
    }
  }

  wp_r := wp_w
  buffer_r := buffer_w
  when(io.out.fire()) {
    wp_r := wp_w - io.out_size
    for(i <- 0 until size) {
      val index = io.out_size + UInt(i)
      buffer_r(i) := Mux(index < UInt(size), buffer_w(index), buffer_w(i))
    }
  }

  wp := wp_r
  buffer := buffer_r

  io.in.ready := wp + UInt(ipw) <= UInt(size)
  io.out.valid := wp >= io.out_size
  io.count := wp
  for(i <- 0 until opw) io.out.bits(i) := buffer(i)
}
