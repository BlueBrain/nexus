package ch.epfl.bluebrain.nexus.delta.sourcing

import cats.effect.{ContextShift, IO, Timer}
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.sourcing.EvaluationError.{EvaluationTimeout, InvalidState}
import fs2.Stream

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
final class StateMachine[State, Command, Event] private (
    initialState: Option[State],
    evaluate: (Option[State], Command) => IO[Event],
    val next: (Option[State], Event) => Option[State]
) {

  /**
    * Fetches the current state and attempt to apply an incoming command on it
    */
  def evaluate(
      current: Option[State],
      command: Command,
      maxDuration: FiniteDuration
  )(implicit contextShift: ContextShift[IO], timer: Timer[IO]): IO[(Event, State)] = {
    val original = current.orElse(initialState)
    for {
      evaluated <- evaluate(original, command).attempt
                     .timeoutTo(maxDuration, IO.raiseError(EvaluationTimeout(command, maxDuration)))
      event     <- IO.fromEither(evaluated)
      newState  <- IO.fromOption(next(original, event))(InvalidState(original, event))
    } yield event -> newState
  }

  /**
    * Compute the state from a stream of events
    */
  def computeState(events: Stream[IO, Event]): IO[Option[State]] = {
    val initial: Either[InvalidState[State, Event], Option[State]] = Right(initialState)
    events
      .fold(initial) {
        case (Right(state), event) => Either.fromOption(next(state, event), InvalidState(state, event)).map(Some(_))
        case (l, _)                => l
      }
      .compile
      .lastOrError
      .flatMap(IO.fromEither(_))
  }
}

object StateMachine {

  def apply[State, Command, Event](
      initialState: Option[State],
      evaluate: (Option[State], Command) => IO[Event],
      next: (Option[State], Event) => Option[State]
  ) = new StateMachine(initialState, evaluate, next)

}
