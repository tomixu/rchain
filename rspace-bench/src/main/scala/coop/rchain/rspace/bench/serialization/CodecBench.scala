package coop.rchain.rspace.bench.serialization

import java.util.concurrent.TimeUnit
import java.nio.ByteBuffer

import coop.rchain.rspace.internal._
import coop.rchain.rspace.examples.AddressBookExample._
import coop.rchain.rspace.examples.AddressBookExample.implicits._

import org.openjdk.jmh.annotations.{State => BenchState, _}
import org.openjdk.jmh.infra.Blackhole

class CodecBench {

  @Benchmark
  @BenchmarkMode(Array(Mode.AverageTime))
  @OutputTimeUnit(TimeUnit.MILLISECONDS)
  @Fork(value = 1)
  @Warmup(iterations = 5)
  @Measurement(iterations = 10)
  def scodecRoundTrip(bh: Blackhole, state: ScodecBenchState) = {
    val res = state.roundTrip(state.gnat)(state.serializer)
    bh.consume(res)
  }

  @Benchmark
  @BenchmarkMode(Array(Mode.AverageTime))
  @OutputTimeUnit(TimeUnit.MILLISECONDS)
  @Fork(value = 1)
  @Warmup(iterations = 5)
  @Measurement(iterations = 10)
  def kryoRoundTrip(bh: Blackhole, state: KryoBenchState) = {
    val res = state.roundTrip(state.gnat)(state.serializer)
    bh.consume(res)
  }
}

trait Serialize2ByteBuffer[A] {
  def encode(a: A): ByteBuffer
  def decode(bytes: ByteBuffer): A
}

abstract class CodecBenchState {
  type TestGNAT = GNAT[Channel, Pattern, Entry, EntriesCaptor]

  implicit def serializer: Serialize2ByteBuffer[TestGNAT]

  def roundTrip[A](e: A)(implicit s: Serialize2ByteBuffer[A]): A = {
    val d = s.encode(e)
    s.decode(d)
  }

  val data = Entry(
    name = Name("Ben", "Serializerovsky"),
    address = Address("1000 Main St", "Crystal Lake", "Idaho", "223322"),
    email = "blablah@tenex.net",
    phone = "555-6969"
  )

  import collection.immutable.Seq

  val channel      = Channel("colleagues")
  val channels     = List(channel, Channel("friends"))
  val datum        = Datum.create(channel, data, false)
  val patterns     = Seq[Pattern](CityMatch(city = "Crystal Lake"))
  val continuation = WaitingContinuation.create(channels, patterns, new EntriesCaptor(), false)

  def gnat() = GNAT[Channel, Pattern, Entry, EntriesCaptor](
    channels,
    Seq(datum),
    Seq(continuation)
  )
}

@BenchState(Scope.Benchmark)
class ScodecBenchState extends CodecBenchState {

  import scodec.Codec
  import scodec.bits.BitVector

  implicit val cg: Codec[GNAT[Channel, Pattern, Entry, EntriesCaptor]] = codecGNAT(
    implicits.serializeChannel.toCodec,
    implicits.serializePattern.toCodec,
    implicits.serializeInfo.toCodec,
    implicits.serializeEntriesCaptor.toCodec
  )
  import coop.rchain.shared.ByteVectorOps._

  implicit def serializer = new Serialize2ByteBuffer[TestGNAT] {

    override def encode(a: TestGNAT): ByteBuffer =
      cg.encode(a).get.toByteVector.toDirectByteBuffer
    override def decode(bytes: ByteBuffer): TestGNAT =
      cg.decode(BitVector(bytes)).get.value
  }
}

@BenchState(Scope.Benchmark)
class KryoBenchState extends CodecBenchState {

  import com.esotericsoftware.kryo.Kryo
  import com.esotericsoftware.kryo.io._

  import com.esotericsoftware.kryo.util.MapReferenceResolver
  import org.objenesis.strategy.StdInstantiatorStrategy

  val kryo = new Kryo()
  kryo.setRegistrationRequired(false)
  // Support deserialization of classes without no-arg constructors
  kryo.setInstantiatorStrategy(new StdInstantiatorStrategy())

  implicit def serializer = new Serialize2ByteBuffer[TestGNAT] {

    override def encode(gnat: TestGNAT): ByteBuffer = {
      val output = new ByteBufferOutput(1024, -1)
      kryo.writeObject(output, gnat)
      output.close()

      val buf = output.getByteBuffer
      buf.flip()
      buf
    }

    override def decode(bytes: ByteBuffer): TestGNAT = {
      val input = new ByteBufferInput(bytes)
      val res   = kryo.readObject(input, classOf[TestGNAT])
      input.close()
      res
    }

  }
}
