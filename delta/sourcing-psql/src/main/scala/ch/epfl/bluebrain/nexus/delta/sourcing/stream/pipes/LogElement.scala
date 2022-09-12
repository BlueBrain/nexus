package ch.epfl.bluebrain.nexus.delta.sourcing.stream.pipes

import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.SuccessElem
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.PipeDef
import monix.bio.Task
import shapeless.Typeable

object LogElement {

  def apply[A: Typeable](label: Label, logger: SuccessElem[A] => Task[Unit]): PipeDef =
    GenericPipe[A, Unit](label, elem => logger(elem).as(elem.void))

}
