package ch.epfl.bluebrain.nexus.delta.sourcing

import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.sourcing.StateMachineError.{EvaluationTimeout, InvalidState}
import ch.epfl.bluebrain.nexus.delta.sourcing.config.SourcingConfig.EvaluationConfig
import fs2.Stream
import monix.bio.{IO, Task, UIO}

final class StateMachine[State, Command, Event, Rejection] private (
    initialState: Option[State],
    evaluate: (Option[State], Command) => IO[Rejection, Event],
    next: (Option[State], Event) => Option[State]
) {

  def evaluate(
      getCurrent: UIO[Option[State]],
      command: Command,
      config: EvaluationConfig
  ): IO[Rejection, (Event, State)] = {
    for {
      original  <- getCurrent.map(_.orElse(initialState))
      evaluated <- evaluate(original, command).attempt
                     .timeoutWith(config.maxDuration, EvaluationTimeout(command, config.maxDuration))
                     .hideErrors
      event     <- IO.fromEither(evaluated)
      newState  <- IO.fromOption(next(original, event), InvalidState(original, event)).hideErrors
    } yield event -> newState
  }

  def computeState(events: Stream[Task, Event]): UIO[Option[State]] = {
    val initial: Either[InvalidState[State, Event], Option[State]] = Right(initialState)
    events
      .fold(initial) {
        case (Right(state), event) => Either.fromOption(next(state, event), InvalidState(state, event)).map(Some(_))
        case (l, _)                => l
      }
      .compile
      .lastOrError
      .hideErrors
      .flatMap(IO.fromEither(_).hideErrors)
  }
}

object StateMachine {

  def apply[State, Command, Event, Rejection](
      initialState: Option[State],
      evaluate: (Option[State], Command) => IO[Rejection, Event],
      next: (Option[State], Event) => Option[State]
  ) = new StateMachine(initialState, evaluate, next)

}
