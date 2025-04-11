package ch.epfl.bluebrain.nexus.delta.sourcing

import ch.epfl.bluebrain.nexus.delta.sourcing.ScopedEntityDefinition.Tagger
import ch.epfl.bluebrain.nexus.delta.sourcing.event.Event.ScopedEvent
import ch.epfl.bluebrain.nexus.delta.sourcing.model.EntityDependency.DependsOn
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EntityType, Tags}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.state.State.ScopedState
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
final case class ScopedEntityDefinition[Id, S <: ScopedState, Command, E <: ScopedEvent, Rejection <: Throwable](
    tpe: EntityType,
    stateMachine: StateMachine[S, Command, E],
    eventSerializer: Serializer[Id, E],
    stateSerializer: Serializer[Id, S],
    tagger: Tagger[S, E],
    extractDependencies: S => Option[Set[DependsOn]],
    onUniqueViolation: (Id, Command) => Rejection
)(implicit val get: Get[Id], val put: Put[Id])

object ScopedEntityDefinition {

  /**
    * Creates an entity definition which is not meant to be tagged
    */
  def untagged[Id, S <: ScopedState, Command, E <: ScopedEvent, Rejection <: Throwable](
      tpe: EntityType,
      stateMachine: StateMachine[S, Command, E],
      eventSerializer: Serializer[Id, E],
      stateSerializer: Serializer[Id, S],
      extractDependencies: S => Option[Set[DependsOn]],
      onUniqueViolation: (Id, Command) => Rejection
  )(implicit get: Get[Id], put: Put[Id]): ScopedEntityDefinition[Id, S, Command, E, Rejection] =
    ScopedEntityDefinition(
      tpe,
      stateMachine,
      eventSerializer,
      stateSerializer,
      Tagger.noTag,
      extractDependencies,
      onUniqueViolation
    )

  /**
    * Defines the tag behaviour
    * @param existingTags
    *   list the existing tags
    * @param tagWhen
    *   to tag the state from the returned revision with the returned state
    * @param untagWhen
    *   to untag the state associated with the given tag
    */
  final case class Tagger[S, E](
      existingTags: S => Option[Tags],
      tagWhen: E => Option[(UserTag, Int)],
      untagWhen: E => Option[UserTag]
  )

  object Tagger {
    def noTag[S, E]: Tagger[S, E] = Tagger(_ => None, _ => None, _ => None)
  }

}
