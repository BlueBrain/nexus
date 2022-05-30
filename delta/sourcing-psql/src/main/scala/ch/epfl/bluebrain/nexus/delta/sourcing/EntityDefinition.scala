package ch.epfl.bluebrain.nexus.delta.sourcing

import ch.epfl.bluebrain.nexus.delta.sourcing.EntityDefinition.{Serializer, Tagger}
import ch.epfl.bluebrain.nexus.delta.sourcing.event.Event
import ch.epfl.bluebrain.nexus.delta.sourcing.model.EntityType
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.state.State
import doobie.{Get, Put}
import io.circe.Codec

/**
  * Defines the required information to be able to handle an entity
  * @param tpe the entity type
  * @param stateMachine its state machine
  * @param eventSerializer how to serialize/deserialize events in database
  * @param stateSerializer how to serialize/deserialize states in database
  * @param tagger when to tag/untag states
  * @param onUniqueViolation to handle gracefully unique constraint violations in database by a rejection
  */
final case class EntityDefinition[Id, S <: State, Command, E <: Event, Rejection](
    tpe: EntityType,
    stateMachine: StateMachine[S, Command, E, Rejection],
    eventSerializer: Serializer[Id, E],
    stateSerializer: Serializer[Id, S],
    tagger: Tagger[E],
    onUniqueViolation: (Id, Command) => Rejection
)(implicit val get: Get[Id], val put: Put[Id])

object EntityDefinition {

  /**
    * Defines how to extract an id from an event/state and how to serialize and deserialize it
    * @param extractId to extract an identifier from an event
    * @param codec the Circe codec to serialize/deserialize the event/state from the database
    */
  final case class Serializer[Id, Value](extractId: Value => Id)(implicit val codec: Codec.AsObject[Value])

  /**
    * Defines when to tag or to untag a state
    * @param tagWhen to tag the state from the returned revision with the returned state
    * @param untagWhen to untag the state associated with the given tag
    */
  final case class Tagger[E](tagWhen: E => Option[(UserTag, Int)], untagWhen: E => Option[UserTag])

}
