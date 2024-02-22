package ch.epfl.bluebrain.nexus.ship

import cats.effect.{IO, Ref}
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF

import java.util.UUID

/**
  * Mutable uuid generator used for the migration
  */
class EventUUIDF(uuid: Ref[IO, UUID]) extends UUIDF {

  def setUUID(value: UUID): IO[Unit] = uuid.set(value)

  override def apply(): IO[UUID] = uuid.get
}

object EventUUIDF {
  def init(): IO[EventUUIDF] = Ref.of[IO, UUID](UUID.randomUUID()).map { ref => new EventUUIDF(ref) }
}
