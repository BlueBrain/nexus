package ch.epfl.bluebrain.nexus.delta.sourcing

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem
import fs2.{Pipe, Stream}

package object model {

  type EnvelopeStream[Value] = Stream[IO, Envelope[Value]]

  type ElemStream[Value] = Stream[IO, Elem[Value]]

  type ElemPipe[In, Out] = Pipe[IO, Elem[In], Elem[Out]]

}
