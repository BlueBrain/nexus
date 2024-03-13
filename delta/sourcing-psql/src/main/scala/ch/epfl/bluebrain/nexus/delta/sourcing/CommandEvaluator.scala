package ch.epfl.bluebrain.nexus.delta.sourcing

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.sourcing.EvaluationError._

import scala.concurrent.duration.FiniteDuration

trait CommandEvaluator[State, Command, Event] {

  def stateMachine: StateMachine[State, Event]

  /**
    * Fetches the current state and attempt to apply an incoming command on it
    */
  def evaluate(
      current: Option[State],
      command: Command,
      maxDuration: FiniteDuration
  ): IO[(Event, State)]

}

object CommandEvaluator {

  def apply[State, Command, Event](
      stm: StateMachine[State, Event],
      eval: (Option[State], Command) => IO[Event]
  ): CommandEvaluator[State, Command, Event] = {

    new CommandEvaluator[State, Command, Event] {
      override def evaluate(
          current: Option[State],
          command: Command,
          maxDuration: FiniteDuration
      ): IO[(Event, State)] = {
        val original = current.orElse(stateMachine.initialState)
        for {
          evaluated <- eval(original, command).attempt
                         .timeoutTo(maxDuration, IO.raiseError(EvaluationTimeout(command, maxDuration)))
          event     <- IO.fromEither(evaluated)
          newState  <- IO.fromOption(stateMachine.next(original, event))(InvalidState(original, event))
        } yield event -> newState
      }

      override def stateMachine: StateMachine[State, Event] = stm
    }
  }

}
