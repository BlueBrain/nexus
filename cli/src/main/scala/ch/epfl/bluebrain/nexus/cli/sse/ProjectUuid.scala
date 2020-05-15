package ch.epfl.bluebrain.nexus.cli.sse

import java.util.UUID

import cats.Show

/**
  * A safe project UUID.
  * @param value the underlying uuid
  */
final case class ProjectUuid(value: UUID)

object ProjectUuid {
  implicit final val projectUuidShow: Show[ProjectUuid] = Show.show { ou => ou.value.toString }

  object unsafe {
    implicit final def projectUuidFromString(string: String): ProjectUuid =
      ProjectUuid(UUID.fromString(string))
  }
}
