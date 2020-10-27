package ch.epfl.bluebrain.nexus.sourcing

import ch.epfl.bluebrain.nexus.sourcing.SnapshotStrategy.SnapshotPredicate

/**
  * A Snapshot configuration that will be made every numberOfEvents and keepNSnapshots will be kept
  * deleteEventsOnSnapshot allows to delete events prior which are older than
  * the older snapshot
  *
  * @param numberOfEvents         the optional frequency we need to trigger snapshots
  * @param keepNSnapshots         the optional number of snapshots we need to keep
  * @param deleteEventsOnSnapshot the optional flag to decide if we need to delete old events
  */
final case class SnapshotStrategyConfig private (
    numberOfEvents: Option[Int],
    keepNSnapshots: Option[Int],
    deleteEventsOnSnapshot: Option[Boolean]
) {
  def strategy: SnapshotStrategy =
    (numberOfEvents, keepNSnapshots, deleteEventsOnSnapshot) match {
      case (Some(nEv), Some(keepN), Some(deleteEvents)) =>
        SnapshotStrategy.SnapshotEvery(nEv, keepN, deleteEvents)
      case _                                            => SnapshotStrategy.NoSnapshot
    }

  def combinedStrategy[State, Event](predicate: SnapshotPredicate[State, Event]): SnapshotStrategy =
    (numberOfEvents, keepNSnapshots, deleteEventsOnSnapshot) match {
      case (Some(nEv), Some(keepN), Some(deleteEvents)) =>
        SnapshotStrategy.SnapshotCombined(predicate, SnapshotStrategy.SnapshotEvery(nEv, keepN, deleteEvents))
      case _                                            => SnapshotStrategy.NoSnapshot
    }
}

object SnapshotStrategyConfig {

  /**
    * Constructs a [[SnapshotStrategyConfig]].
    *
    * @param numberOfEvents         the optional frequency we need to trigger snapshots
    * @param keepNSnapshots         the optional number of snapshots we need to keep
    * @param deleteEventsOnSnapshot the optional flag to decide if we need to delete old events
    */
  final def apply(
      numberOfEvents: Option[Int],
      keepNSnapshots: Option[Int],
      deleteEventsOnSnapshot: Option[Boolean]
  ): Option[SnapshotStrategyConfig] =
    Option.when(
      numberOfEvents.isDefined && keepNSnapshots.isDefined && deleteEventsOnSnapshot.isDefined ||
        numberOfEvents.isEmpty && keepNSnapshots.isEmpty && deleteEventsOnSnapshot.isEmpty
    )(new SnapshotStrategyConfig(numberOfEvents, keepNSnapshots, deleteEventsOnSnapshot))
}
