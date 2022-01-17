package ch.epfl.bluebrain.nexus.delta.sourcing2.state

import ch.epfl.bluebrain.nexus.delta.sourcing2.model.{EntityId, EntityType, Tag}
import io.circe.Encoder
import io.circe.syntax._

import java.time.Instant

/**
  * Serializes a state and its metadata into an [[StateRow]]
  */
sealed trait StateEncoder[State] {

  def serialize(
      entityType: EntityType,
      entityId: EntityId,
      state: State,
      tracks: Iterable[Int],
      tag: Tag,
      now: Instant,
      writeVersion: String
  ): StateRow

}

object StateEncoder {

  def apply[State: Encoder.AsObject](
      revision: State => Int,
      instant: State => Instant
  ): StateEncoder[State] = new StateEncoder[State] {
    override def serialize(
        entityType: EntityType,
        entityId: EntityId,
        state: State,
        tracks: Iterable[Int],
        tag: Tag,
        now: Instant,
        writeVersion: String
    ): StateRow =
      StateRow(
        Long.MinValue,
        entityType,
        entityId,
        revision(state),
        state.asJson,
        tracks.toList,
        tag,
        instant(state),
        now,
        writeVersion
      )
  }
}
