package ch.epfl.bluebrain.nexus.delta.sourcing

import cats.effect.IO
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.sourcing.EvaluationError.InvalidState
import fs2.Stream

/**
  * Defines the state machine for an entity
  * @param initialState
  *   the initial state
  * @param next
  *   the function allowing to replay a state from a list of events
  */
final case class StateMachine[State, Event](
    initialState: Option[State],
    next: (Option[State], Event) => Option[State]
) {

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
