package ch.epfl.bluebrain.nexus.delta.sourcing2

import ch.epfl.bluebrain.nexus.delta.sourcing2.model.{EntityId, EntityType, Envelope}
import ch.epfl.bluebrain.nexus.delta.sourcing2.offset.Offset
import fs2.Stream
import monix.bio.{Task, UIO}

trait EventLog {

  def eventsByPersistenceId[Event](tpe: EntityType, id: EntityId, end: Int): Stream[Task, Envelope[Event]]

  def currentEventsByPersistenceId[Event](tpe: EntityType, id: EntityId, end: Int): Stream[Task, Envelope[Event]]

  def state[Event, State](tpe: EntityType, id: EntityId)(implicit replay: StateReplay[Event, State]): UIO[Option[State]]

  def stateAt[Event, State](tpe: EntityType, id: EntityId, rev: Int)(implicit
      replay: StateReplay[Event, State]
  ): UIO[Option[State]]

  def currentEvents[Event](tag: String, offset: Offset): Stream[Task, Envelope[Event]]

  def events[Event](tag: String, offset: Offset): Stream[Task, Envelope[Event]]

}
