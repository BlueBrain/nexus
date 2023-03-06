package ch.epfl.bluebrain.nexus.delta.sdk.migration

import ch.epfl.bluebrain.nexus.delta.sourcing.model.EntityType
import io.circe.Json

import java.time.Instant
import java.util.UUID

final case class ToMigrateEvent(
    entityType: EntityType,
    persistenceId: String,
    sequenceNr: Long,
    payload: Json,
    instant: Instant,
    offset: UUID
)
