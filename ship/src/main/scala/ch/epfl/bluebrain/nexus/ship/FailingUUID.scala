package ch.epfl.bluebrain.nexus.ship

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF

import java.util.UUID

/**
  * In this batch, we should not use any id so it helps prevent it
  */
// $COVERAGE-OFF$
object FailingUUID extends UUIDF {
  override def apply(): IO[UUID] =
    IO.raiseError(new IllegalStateException("Generation of UUID is now allowed as we don't expect id generation"))
}
// $COVERAGE-ON$
