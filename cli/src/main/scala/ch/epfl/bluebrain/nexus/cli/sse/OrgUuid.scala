package ch.epfl.bluebrain.nexus.cli.sse

import java.util.UUID

import cats.Show

/**
  * A safe organization UUID.
  * @param value the underlying uuid
  */
final case class OrgUuid(value: UUID)

object OrgUuid {
  implicit final val orgUuidShow: Show[OrgUuid] = Show.show { ou => ou.value.toString }

  object unsafe {
    implicit final def orgUuidFromString(string: String): OrgUuid =
      OrgUuid(UUID.fromString(string))
  }
}
