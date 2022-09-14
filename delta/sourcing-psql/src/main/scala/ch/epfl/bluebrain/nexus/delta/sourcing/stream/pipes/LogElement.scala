package ch.epfl.bluebrain.nexus.delta.sourcing.stream.pipes

import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.SuccessElem
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.PipeDef
import monix.bio.Task
import shapeless.Typeable

/**
  * A terminating pipe/pipedef pair that logs elements with the provided `logger` and discards the underlying element
  * value.
  */
object LogElement {

  /**
    * Constructs a terminating pipe/pipedef pair that logs elements with the provided `logger` and discards the
    * underlying element value.
    * @param label
    *   the pipe/pipedef label
    * @param logger
    *   the logging function
    * @tparam A
    *   the input element type
    */
  def apply[A: Typeable](label: Label, logger: SuccessElem[A] => Task[Unit]): PipeDef =
    GenericPipe[A, Unit](label, elem => logger(elem).as(elem.void))

}
