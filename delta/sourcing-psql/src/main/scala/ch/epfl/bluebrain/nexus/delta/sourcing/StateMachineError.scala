package ch.epfl.bluebrain.nexus.delta.sourcing

import scala.concurrent.duration.FiniteDuration

sealed abstract class StateMachineError extends Exception with Product with Serializable { self =>
  override def fillInStackTrace(): Throwable = self
}

object StateMachineError {

  final case class InvalidState[State, Event](state: Option[State], event: Event) extends StateMachineError

  final case class EvaluationTimeout[Command](value: Command, timeoutAfter: FiniteDuration) extends StateMachineError
  final case class EvaluationFailure[Command](value: Command, message: Option[String])      extends StateMachineError

}
