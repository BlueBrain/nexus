package ch.epfl.bluebrain.nexus.delta.sourcing2.event

import ch.epfl.bluebrain.nexus.delta.sourcing2.model.{EntityId, EntityScope, EntityType}
import io.circe.Encoder
import io.circe.syntax._

import java.time.Instant

/**
  * Serializes an event and its metadata into an [[EventRow]]
  */
sealed trait EventEncoder[Event] {

  def serialize(
      entityType: EntityType,
      entityId: EntityId,
      event: Event,
      tracks: Iterable[Int],
      now: Instant,
      writeVersion: String
  ): EventRow

}

object EventEncoder {

  def apply[Event: Encoder.AsObject](
      revision: Event => Int,
      instant: Event => Instant,
      scope: Option[EntityScope]
  ): EventEncoder[Event] = new EventEncoder[Event] {
    override def serialize(
        entityType: EntityType,
        entityId: EntityId,
        event: Event,
        tracks: Iterable[Int],
        now: Instant,
        writeVersion: String
    ): EventRow =
      EventRow(
        Long.MinValue,
        entityType,
        entityId,
        revision(event),
        scope.getOrElse(EntityScope(entityType.value)),
        event.asJson,
        tracks.toList,
        instant(event),
        now,
        writeVersion
      )
  }
}
