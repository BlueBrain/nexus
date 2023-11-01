package ch.epfl.bluebrain.nexus.delta.sourcing.stream

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Operation.Sink
import fs2.Chunk
import shapeless.Typeable

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.duration._

class NoopSink[A: Typeable] extends Sink {

  override type In = A

  override def inType: Typeable[A] = Typeable[A]

  override def chunkSize: Int = 1

  override def maxWindow: FiniteDuration = 10.millis

  override def apply(elements: Chunk[Elem[A]]): IO[Chunk[Elem[Unit]]] =
    IO.pure(elements.map(_.void))
}
