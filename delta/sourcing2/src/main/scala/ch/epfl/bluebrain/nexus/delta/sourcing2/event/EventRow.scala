package ch.epfl.bluebrain.nexus.delta.sourcing2.event

import ch.epfl.bluebrain.nexus.delta.sourcing2.model.{EntityId, EntityScope, EntityType}
import io.circe.Json

import java.time.Instant

/**
  * Row from from the `events` table
  */
final case class EventRow(
    ordering: Long,
    tpe: EntityType,
    id: EntityId,
    revision: Int,
    scope: EntityScope,
    payload: Json,
    tracks: List[Int],
    instant: Instant,
    writtenAt: Instant,
    writeVersion: String
)
