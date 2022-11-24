package ch.epfl.bluebrain.nexus.delta.sourcing.stream.pipes

import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.SuccessElem
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Operation.Pipe
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.{Elem, PipeDef}
import monix.bio.Task
import shapeless.Typeable

object CustomId {

  private val label = Label.unsafe("customId")

  private def elemToId[A]: SuccessElem[A] => Task[Elem[A]] = elem =>
    Task.pure {
      elem.copy(id = elem.id + s"?rev=${elem.revision}")
    }

  /**
    * @return
    *   a pipe that substitutes the ID of an Elem[A] using a function
    */
  def pipe[A: Typeable]: Pipe =
    new GenericPipe[A, A](label, elemToId)

  def apply[A: Typeable]: PipeDef =
    GenericPipe[A, A](label, elemToId)

}
