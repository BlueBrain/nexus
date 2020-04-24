package ch.epfl.bluebrain.nexus.cli.sse

import java.util.UUID

/**
  * A safe project UUID.
  * @param value the underlying uuid
  */
final case class ProjectUuid(value: UUID)
