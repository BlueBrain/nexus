package ch.epfl.bluebrain.nexus.delta.sourcing

import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClassUtils

import scala.concurrent.duration.FiniteDuration

/**
  * Error that may occur when evaluating commands or replaying a state
  */
sealed abstract class EvaluationError(message: String) extends Exception(message) with Product with Serializable {
  self =>
  override def fillInStackTrace(): Throwable = self
}

object EvaluationError {

  /**
    * Error occurring when applying an event to a state result in an invalid one
    * @param state
    *   the original state
    * @param event
    *   the event to apply
    */
  final case class InvalidState[State, Event](state: Option[State], event: Event)
      extends EvaluationError(s"Applying the event $event on the original state $state resulted in an invalid state")

  /**
    * Error occurring when the evaluation of a command exceeds the defined timeout
    * @param command
    *   the command that failed
    * @param timeoutAfter
    *   the timeout that was applied
    */
  final case class EvaluationTimeout[Command](command: Command, timeoutAfter: FiniteDuration)
      extends EvaluationError(s"'$command' received a timeout after $timeoutAfter")

  /**
    * Error occurring when the evaluation of a command raised an error
    * @param command
    *   the command that failed
    * @param errorType
    *   the type of error that was raised
    * @param errorMessage
    *   the type of error that was raised
    */
  final case class EvaluationFailure[Command](command: Command, errorType: String, errorMessage: String)
      extends EvaluationError(s"'$command' failed with an error '$errorType' and a message $errorMessage")

  object EvaluationFailure {

    def apply[Command](command: Command, throwable: Throwable): EvaluationFailure[Command] =
      EvaluationFailure(command, ClassUtils.simpleName(throwable), throwable.getMessage)

  }

}
