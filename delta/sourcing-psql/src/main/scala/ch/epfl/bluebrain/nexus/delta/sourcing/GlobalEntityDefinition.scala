package ch.epfl.bluebrain.nexus.delta.sourcing

import ch.epfl.bluebrain.nexus.delta.sourcing.event.Event
import ch.epfl.bluebrain.nexus.delta.sourcing.model.EntityType
import ch.epfl.bluebrain.nexus.delta.sourcing.state.State
import doobie.{Get, Put}

/**
  * Defines the required information to be able to handle a global entity
  * @param tpe
  *   the entity type
  * @param stateMachine
  *   its state machine
  * @param eventSerializer
  *   how to serialize/deserialize events in database
  * @param stateSerializer
  *   how to serialize/deserialize states in database
  * @param onUniqueViolation
  *   to handle gracefully unique constraint violations in database by a rejection
  */
final case class GlobalEntityDefinition[Id, S <: State, Command, E <: Event, Rejection](
    tpe: EntityType,
    stateMachine: StateMachine[S, Command, E, Rejection],
    eventSerializer: Serializer[Id, E],
    stateSerializer: Serializer[Id, S],
    onUniqueViolation: (Id, Command) => Rejection
)(implicit val get: Get[Id], val put: Put[Id])
