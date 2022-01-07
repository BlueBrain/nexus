package ch.epfl.bluebrain.nexus.delta.sourcing2.state

import ch.epfl.bluebrain.nexus.delta.sourcing2.model.{EntityId, EntityType}
import io.circe.Encoder
import io.circe.syntax._

import java.time.Instant

/**
  * Serializes a state and its metadata into an [[StateRow]]
  */
sealed trait StateSerializer[State] {

  def serialize(
      entityType: EntityType,
      entityId: EntityId,
      state: State,
      tracks: Iterable[Int],
      tag: Option[String],
      now: Instant,
      writeVersion: String
  ): StateRow

}

object StateSerializer {

  def apply[State: Encoder.AsObject](
      revision: State => Int,
      instant: State => Instant
  ): StateSerializer[State] = new StateSerializer[State] {
    override def serialize(
        entityType: EntityType,
        entityId: EntityId,
        state: State,
        tracks: Iterable[Int],
        tag: Option[String],
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
