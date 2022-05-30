package ch.epfl.bluebrain.nexus.delta.sourcing

import ch.epfl.bluebrain.nexus.delta.sourcing.EntityDefinition.{Serializer, Tagger}
import ch.epfl.bluebrain.nexus.delta.sourcing.event.Event
import ch.epfl.bluebrain.nexus.delta.sourcing.model.EntityType
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.state.State
import doobie.{Get, Put}
import io.circe.Codec

final case class EntityDefinition[Id, S <: State, Command, E <: Event, Rejection](
    tpe: EntityType,
    stateMachine: StateMachine[S, Command, E, Rejection],
    eventSerializer: Serializer[Id, E],
    stateSerializer: Serializer[Id, S],
    tagger: Tagger[E],
    onUniqueViolation: (Id, Command) => Rejection
)(implicit val get: Get[Id], val put: Put[Id])

object EntityDefinition {

  final case class Serializer[Id, Value](extractId: Value => Id)(implicit val codec: Codec.AsObject[Value])

  final case class Tagger[E](tagWhen: E => Option[(UserTag, Int)], untagWhen: E => Option[UserTag])

}
