package ch.epfl.bluebrain.nexus.delta.sourcing

/**
  * Snapshot strategy to apply to a persistent [[ch.epfl.bluebrain.nexus.delta.sourcing.processor.EventSourceProcessor]]
  * See <https://doc.akka.io/docs/akka/current/typed/persistence-snapshot.html>
  */
sealed trait SnapshotStrategy extends Product with Serializable

object SnapshotStrategy {

  /**
    * No snapshot will be made
    */
  case object NoSnapshot extends SnapshotStrategy

  /**
    * A Snapshot will be made every numberOfEvents and keepNSnapshots will be kept deleteEventsOnSnapshot allows to
    * delete events prior which are older than the older snapshot
    *
    * @param numberOfEvents
    *   the frequency we need to trigger snapshots
    * @param keepNSnapshots
    *   the number of snapshots we need to keep
    * @param deleteEventsOnSnapshot
    *   if we need to delete old events
    */
  final case class SnapshotEvery(numberOfEvents: Int, keepNSnapshots: Int, deleteEventsOnSnapshot: Boolean)
      extends SnapshotStrategy
}
