package ch.epfl.bluebrain.nexus.sourcingnew

import akka.persistence.typed.scaladsl.{EventSourcedBehavior, RetentionCriteria, SnapshotCountRetentionCriteria}

/**
  * Snapshot strategy to apply to a [[ch.epfl.bluebrain.nexus.sourcingnew.processor.EventSourceProcessor.PersistentEventProcessor]]
  * See <https://doc.akka.io/docs/akka/current/typed/persistence-snapshot.html>
  */
sealed trait SnapshotStrategy

object SnapshotStrategy {

  /**
    * No snapshot will be made
    */
  case object NoSnapshot extends SnapshotStrategy

  /**
    * Snapshot will occur when the predicate
    * with the State, Event and sequence is satisfied
    * @param predicate
    * @tparam State
    * @tparam Event
    */
  final case class SnapshotPredicate[State, Event](predicate: (State, Event, Long) => Boolean) extends SnapshotStrategy

  /**
    * A Snapshot will be made every numberOfEvents and keepNSnapshots will be kept
    * deleteEventsOnSnapshot allows to delete events prior which are older than
    * the older snapshot
    *
    * @param numberOfEvents
    * @param keepNSnapshots
    * @param deleteEventsOnSnapshot
    */
  final case class SnapshotEvery(numberOfEvents: Int,
                                 keepNSnapshots: Int,
                                 deleteEventsOnSnapshot: Boolean = false) extends  SnapshotStrategy {

    def toSnapshotCriteria: SnapshotCountRetentionCriteria = {
      val criteria = RetentionCriteria.snapshotEvery(numberOfEvents, keepNSnapshots)
      if(deleteEventsOnSnapshot)
        criteria.withDeleteEventsOnSnapshot
      else
        criteria
    }

  }

  /**
    * Combine the [[SnapshotPredicate]] and the [[SnapshotEvery]] strategies
    * @param predicate
    * @param snapshotEvery
    * @tparam State
    * @tparam Event
    */
  final case class SnapshotCombined[State, Event](predicate: SnapshotPredicate[State, Event],
                                                  snapshotEvery: SnapshotEvery) extends  SnapshotStrategy

  implicit class EventSourcedBehaviorOps[C, E, State]
    (val eventSourcedBehavior: EventSourcedBehavior[C, E, State]) extends AnyVal {

    def snapshotStrategy(strategy: SnapshotStrategy): EventSourcedBehavior[C, E, State] =
      strategy match {
        case NoSnapshot => eventSourcedBehavior
        case s: SnapshotPredicate[State, E] =>
            eventSourcedBehavior.snapshotWhen(s.predicate)
        case s: SnapshotEvery =>
          eventSourcedBehavior.withRetention(s.toSnapshotCriteria)
        case s: SnapshotCombined[State, E] =>
          eventSourcedBehavior.snapshotWhen(s.predicate.predicate)
            .withRetention(s.snapshotEvery.toSnapshotCriteria)
      }
  }

}
