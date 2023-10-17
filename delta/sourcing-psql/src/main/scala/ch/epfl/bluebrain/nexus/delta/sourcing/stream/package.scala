package ch.epfl.bluebrain.nexus.delta.sourcing

import cats.effect.IO
import monix.bio.Task

package object stream {
  type Operation = OperationF[Task]

  type Source = SourceF[Task]

  type SourceCatsEffect = SourceF[IO]
}
