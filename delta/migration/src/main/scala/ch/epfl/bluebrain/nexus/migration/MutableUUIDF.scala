package ch.epfl.bluebrain.nexus.migration

import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import monix.bio.UIO

import java.util.UUID

/**
  * Mutable uuid generator used for the migration
  */
class MutableUUIDF(var uuid: UUID) extends UUIDF {

  def setUUID(value: UUID): Unit = uuid = value

  override def apply(): UIO[UUID] = UIO.pure(uuid)
}
