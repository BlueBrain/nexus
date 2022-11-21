package ch.epfl.bluebrain.nexus.delta.sourcing.stream.pipes

import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.SuccessElem
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Operation.Pipe
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.{Elem, PipeDef}
import io.circe.syntax.EncoderOps
import io.circe.{Encoder, Json}
import monix.bio.Task
import shapeless.Typeable

object AsJson {

  private val label = Label.unsafe("asJson")

  private def fn[A: Encoder]: SuccessElem[A] => Task[Elem[Json]] =
    elem =>
      Task {
        elem.copy(value = elem.value.asJson)
      }

  def pipe[A: Typeable: Encoder]: Pipe =
    new GenericPipe[A, Json](label, fn)

  def apply[A: Typeable: Encoder]: PipeDef =
    GenericPipe[A, Json](label, fn)

}
