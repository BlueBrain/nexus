package ch.epfl.bluebrain.nexus.delta.sourcing

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem
import fs2.Stream
import fs2.Pipe
import monix.bio.Task

package object model {

  type EnvelopeStream[Value] = Stream[IO, Envelope[Value]]

  type ElemStream[Value] = Stream[Task, Elem[Value]]

  type ElemPipe[In, Out] = Pipe[Task, Elem[In], Elem[Out]]

}
