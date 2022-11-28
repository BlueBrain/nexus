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

  private def elemValueToJson[A: Encoder]: SuccessElem[A] => Task[Elem[Json]] =
    elem =>
      Task.pure {
        elem.copy(value = elem.value.asJson)
      }

  /**
    * @return
    *   a pipe that converts an Elem[A] into an Elem[Json] using its Encoder
    */
  def pipe[A: Typeable: Encoder]: Pipe =
    new GenericPipe[A, Json](label, elemValueToJson)

  def apply[A: Typeable: Encoder]: PipeDef =
    GenericPipe[A, Json](label, elemValueToJson)

}
