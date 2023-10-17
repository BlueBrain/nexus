package ch.epfl.bluebrain.nexus.delta.sourcing

import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem
import fs2.Stream
import fs2.Pipe
import monix.bio.Task
import cats.effect.IO

package object model {

  type EnvelopeStream[Value] = Stream[Task, Envelope[Value]]

  type ElemStream[Value] = Stream[Task, Elem[Value]]

  type ElemPipe[In, Out] = ElemPipeF[Task, In, Out]

  type ElemPipeF[F[_], In, Out] = Pipe[F, Elem[In], Elem[Out]]

  type ElemStreamCats[Value] = Stream[IO, Elem[Value]]

}
