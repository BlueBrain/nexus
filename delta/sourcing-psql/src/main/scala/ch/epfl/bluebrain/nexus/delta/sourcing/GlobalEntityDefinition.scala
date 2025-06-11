package ch.epfl.bluebrain.nexus.delta.sourcing

import ch.epfl.bluebrain.nexus.delta.sourcing.event.Event.GlobalEvent
import ch.epfl.bluebrain.nexus.delta.sourcing.model.EntityType
import ch.epfl.bluebrain.nexus.delta.sourcing.state.ProjectionStateSave
import ch.epfl.bluebrain.nexus.delta.sourcing.state.State.GlobalState
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
  * @param projectionStateSave
  *   optional save operation allowing to have a projection of the state in database
  */
final case class GlobalEntityDefinition[Id, S <: GlobalState, Command, E <: GlobalEvent, Rejection <: Throwable](
    tpe: EntityType,
    stateMachine: StateMachine[S, Command, E],
    eventSerializer: Serializer[Id, E],
    stateSerializer: Serializer[Id, S],
    onUniqueViolation: (Id, Command) => Rejection,
    projectionStateSave: ProjectionStateSave[Id, S] = ProjectionStateSave.noop[Id, S]
)(implicit val get: Get[Id], val put: Put[Id])
