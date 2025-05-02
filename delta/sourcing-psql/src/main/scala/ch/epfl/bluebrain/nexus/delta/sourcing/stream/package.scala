package ch.epfl.bluebrain.nexus.delta.sourcing

import cats.effect.IO
import fs2.{Chunk, Pipe, Stream}

package object stream {

  type SuccessElemStream[Value] = Stream[IO, Elem.SuccessElem[Value]]

  type ElemStream[Value] = Stream[IO, Elem[Value]]

  type ElemPipe[In, Out] = Pipe[IO, Elem[In], Elem[Out]]

  type ElemChunk[Value] = Chunk[Elem[Value]]

}
