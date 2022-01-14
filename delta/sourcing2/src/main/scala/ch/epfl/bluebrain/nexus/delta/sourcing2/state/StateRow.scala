package ch.epfl.bluebrain.nexus.delta.sourcing2.state

import ch.epfl.bluebrain.nexus.delta.sourcing2.model.{EntityId, EntityType, Tag}
import io.circe.Json

import java.time.Instant

/**
  * Row from the `states` table
  */
case class StateRow(
    ordering: Long,
    tpe: EntityType,
    id: EntityId,
    revision: Int,
    payload: Json,
    tracks: List[Int],
    tag: Tag,
    updatedAt: Instant,
    writtenAt: Instant,
    writeVersion: String
)
