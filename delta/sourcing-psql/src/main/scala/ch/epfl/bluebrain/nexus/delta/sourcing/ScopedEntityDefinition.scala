package ch.epfl.bluebrain.nexus.delta.sourcing

import ch.epfl.bluebrain.nexus.delta.sourcing.ScopedEntityDefinition.Tagger
import ch.epfl.bluebrain.nexus.delta.sourcing.event.Event
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EntityDependency, EntityType}
import ch.epfl.bluebrain.nexus.delta.sourcing.state.State
import doobie.{Get, Put}

/**
  * Defines the required information to be able to handle an scoped entity
  * @param tpe
  *   the entity type
  * @param stateMachine
  *   its state machine
  * @param eventSerializer
  *   how to serialize/deserialize events in database
  * @param stateSerializer
  *   how to serialize/deserialize states in database
  * @param tagger
  *   when to tag/untag states
  * @param extractDependencies
  *   extract entities the entity relies on
  * @param onUniqueViolation
  *   to handle gracefully unique constraint violations in database by a rejection
  */
final case class ScopedEntityDefinition[Id, S <: State, Command, E <: Event, Rejection](
    tpe: EntityType,
    stateMachine: StateMachine[S, Command, E, Rejection],
    eventSerializer: Serializer[Id, E],
    stateSerializer: Serializer[Id, S],
    tagger: Tagger[E],
    extractDependencies: S => Option[Set[EntityDependency]],
    onUniqueViolation: (Id, Command) => Rejection
)(implicit val get: Get[Id], val put: Put[Id])

object ScopedEntityDefinition {

  /**
    * Creates an entity definition which is not meant to be tagged
    */
  def untagged[Id, S <: State, Command, E <: Event, Rejection](
      tpe: EntityType,
      stateMachine: StateMachine[S, Command, E, Rejection],
      eventSerializer: Serializer[Id, E],
      stateSerializer: Serializer[Id, S],
      onUniqueViolation: (Id, Command) => Rejection
  )(implicit get: Get[Id], put: Put[Id]): ScopedEntityDefinition[Id, S, Command, E, Rejection] =
    ScopedEntityDefinition(
      tpe,
      stateMachine,
      eventSerializer,
      stateSerializer,
      Tagger(_ => None, _ => None),
      _ => None,
      onUniqueViolation
    )

  /**
    * Defines when to tag or to untag a state
    * @param tagWhen
    *   to tag the state from the returned revision with the returned state
    * @param untagWhen
    *   to untag the state associated with the given tag
    */
  final case class Tagger[E](tagWhen: E => Option[(UserTag, Int)], untagWhen: E => Option[UserTag])

}
