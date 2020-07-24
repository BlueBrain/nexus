package ch.epfl.bluebrain.nexus.sourcingnew.aggregate

import akka.persistence.typed.scaladsl.{EventSourcedBehavior, RetentionCriteria, SnapshotCountRetentionCriteria}

sealed trait SnapshotStrategy

object SnapshotStrategy {

  case object NoSnapshot extends SnapshotStrategy

  final case class SnapshotPredicate[State, Event](predicate: (State, Event, Long) => Boolean) extends SnapshotStrategy

  final case class SnapshotEvery(numberOfEvents: Int,
                                 keepNSnapshots: Int,
                                 deleteEventsOnSnapshot: Boolean = false) extends  SnapshotStrategy {

    private [aggregate] def toSnapshotCriteria: SnapshotCountRetentionCriteria = {
      val criteria = RetentionCriteria.snapshotEvery(numberOfEvents, keepNSnapshots)
      if(deleteEventsOnSnapshot)
        criteria.withDeleteEventsOnSnapshot
      else
        criteria
    }

  }

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
