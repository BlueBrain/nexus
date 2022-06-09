package ch.epfl.bluebrain.nexus.delta.sourcing

import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.sourcing.EvaluationError.{EvaluationTimeout, InvalidState}
import fs2.Stream
import monix.bio.{IO, Task, UIO}

import scala.concurrent.duration.FiniteDuration

/**
  * Defines the state machine for an entity
  * @param initialState
  *   the initial state
  * @param evaluate
  *   the function attempting to create a new event and state from an incoming command
  * @param next
  *   the function allowing to replay a state from a list of events
  */
final class StateMachine[State, Command, Event, Rejection] private (
    initialState: Option[State],
    evaluate: (Option[State], Command) => IO[Rejection, Event],
    next: (Option[State], Event) => Option[State]
) {

  /**
    * Fetches the current state and attempt to apply an incoming command on it
    */
  def evaluate(
      getCurrent: UIO[Option[State]],
      command: Command,
      maxDuration: FiniteDuration
  ): IO[Rejection, (Event, State)] = {
    for {
      original  <- getCurrent.map(_.orElse(initialState))
      evaluated <- evaluate(original, command).attempt
                     .timeoutWith(maxDuration, EvaluationTimeout(command, maxDuration))
                     .hideErrors
      event     <- IO.fromEither(evaluated)
      newState  <- IO.fromOption(next(original, event), InvalidState(original, event)).hideErrors
    } yield event -> newState
  }

  /**
    * Compute the state from a stream of events
    */
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
