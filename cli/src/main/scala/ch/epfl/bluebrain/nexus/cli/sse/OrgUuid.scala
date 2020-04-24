package ch.epfl.bluebrain.nexus.cli.sse

import java.util.UUID

/**
  * A safe organization UUID.
  * @param value the underlying uuid
  */
final case class OrgUuid(value: UUID)
