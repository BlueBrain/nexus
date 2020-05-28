package ch.epfl.bluebrain.nexus.sourcing

/**
  * A log of ordered events for uniquely identifiable entities that also maintains a state for each individual entity.
  *
  * @tparam F[_]       the event log effect type
  * @tparam Identifier the type of identifier for entities
  * @tparam Event      the event type
  * @tparam State      the state type
  */
trait StatefulEventLog[F[_], Identifier, Event, State] extends EventLog[F, Identifier, Event] {

  /**
    * The current state of the entity with id __id__.
    *
    * @param id the entity identifier
    * @return the current state of the entity with id __id__
    */
  def currentState(id: Identifier): F[State]

  /**
    * Takes a snapshot of the current state of the entity with id __id__.
    *
    * @param id the entity identifier
    * @return the sequence number corresponding to the snapshot taken
    */
  def snapshot(id: Identifier): F[Long]

}
