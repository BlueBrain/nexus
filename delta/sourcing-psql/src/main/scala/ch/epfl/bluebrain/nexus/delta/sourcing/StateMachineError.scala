package ch.epfl.bluebrain.nexus.delta.sourcing

import scala.concurrent.duration.FiniteDuration

/**
  * Error that may occur when evaluating commands or replaying a state
  */
sealed abstract class StateMachineError extends Exception with Product with Serializable { self =>
  override def fillInStackTrace(): Throwable = self
}

object StateMachineError {

  /**
    * Error occurring when applying an event to a state result in an invalid one
    * @param state
    * @param event
    */
  final case class InvalidState[State, Event](state: Option[State], event: Event) extends StateMachineError

  /**
    * Error occurring when the evaluation of a command exceeds the defined timeout
    * @param command
    *   the command that failed
    * @param timeoutAfter
    *   the timeout that was applied
    */
  final case class EvaluationTimeout[Command](command: Command, timeoutAfter: FiniteDuration) extends StateMachineError

}
