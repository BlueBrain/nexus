package ch.epfl.bluebrain.nexus.delta.sourcing

import ch.epfl.bluebrain.nexus.delta.sourcing.EntityDefinition.{Serializer, StateMachine, Tagger}
import ch.epfl.bluebrain.nexus.delta.sourcing.EvaluationError.EvaluationTimeout
import ch.epfl.bluebrain.nexus.delta.sourcing.config.SourcingConfig.EvaluationConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.event.Event
import ch.epfl.bluebrain.nexus.delta.sourcing.model.EntityType
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.state.State
import doobie.{Get, Put}
import fs2.Stream
import io.circe.Codec
import monix.bio.{IO, Task, UIO}

final case class EntityDefinition[Id, S <: State, Command, E <: Event, Rejection](
    tpe: EntityType,
    stateMachine: StateMachine[S, Command, E, Rejection],
    eventSerializer: Serializer[Id, E],
    stateSerializer: Serializer[Id, S],
    tagger: Tagger[E]
)(implicit val get: Get[Id], val put: Put[Id])

object EntityDefinition {

  final case class Serializer[Id, Value](extractId: Value => Id)(implicit val codec: Codec.AsObject[Value])

  final case class StateMachine[S, Command, E, Rejection](
      initialState: Option[S],
      evaluate: (Option[S], Command) => IO[Rejection, E],
      next: (Option[S], E) => S
  ) {

    def evaluate(
        getCurrent: UIO[Option[S]],
        command: Command,
        config: EvaluationConfig
    ): IO[Rejection, (E, S)] = {
      for {
        original  <- getCurrent.map(_.orElse(initialState))
        evaluated <- evaluate(original, command).attempt
                       .timeoutWith(config.maxDuration, EvaluationTimeout(command, config.maxDuration))
                       .hideErrors
        result    <- IO.fromEither(evaluated).map(event => event -> next(original, event))
      } yield result
    }

    def computeState(events: Stream[Task, E]): UIO[Option[S]] =
      events
        .fold(initialState) { case (state, event) => Some(next(state, event)) }
        .compile
        .lastOrError
        .hideErrors
  }

  final case class Tagger[E](tagWhen: E => Option[(UserTag, Int)], untagWhen: E => Option[UserTag])

}
