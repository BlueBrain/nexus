package ch.epfl.bluebrain.nexus.sourcing.akka.statemachine

import akka.actor.NotInfluenceReceiveTimeout
import ch.epfl.bluebrain.nexus.sourcing.akka.Msg

/**
  * Enumeration that defines the message types exchanged with the underlying State Machine actor.
  */
sealed private[statemachine] trait StateMachineMsg extends Msg

private[statemachine] object StateMachineMsg {

  /**
    * Message to retrieve the current state of a state machine.
    *
    * @param id the persistence id
    */
  case class GetCurrentState private[GetCurrentState] (id: String) extends StateMachineMsg

  object GetCurrentState {

    final def apply(id: String, influenceInvalidation: Boolean): GetCurrentState =
      if (influenceInvalidation) GetCurrentState(id)
      else new GetCurrentState(id) with NotInfluenceReceiveTimeout
  }

  /**
    * Message for exchanging the current state of a state machine.
    *
    * @param id    the persistence id
    * @param state the current state of the event log
    * @tparam State the type of the event log state
    */
  final case class CurrentState[State](id: String, state: State) extends StateMachineMsg with NotInfluenceReceiveTimeout

  /**
    * Message to evaluate a command against a state machine.
    *
    * @param id  the persistence id
    * @param cmd the command to evaluate
    * @tparam Command the type of the command to evaluate
    */
  case class Evaluate[Command] private[Evaluate] (id: String, cmd: Command) extends StateMachineMsg

  object Evaluate {
    final def apply[Command](id: String, cmd: Command): Evaluate[Command] =
      cmd match {
        case _: NotInfluenceReceiveTimeout => new Evaluate(id, cmd) with NotInfluenceReceiveTimeout
        case _                             => new Evaluate(id, cmd)
      }
  }

  /**
    * Message for replying with the outcome of evaluating a command against a state machine.
    *
    * @param id    the persistence id
    * @param value either a rejection or the (state, event) generated from the last command evaluation
    * @tparam Rejection the type of rejection
    * @tparam State  the type of the event log state
    */
  final case class Evaluated[Rejection, State](id: String, value: Either[Rejection, State])
      extends StateMachineMsg
      with NotInfluenceReceiveTimeout

  /**
    * Message to check a command against a state machine.
    *
    * @param id  the persistence id
    * @param cmd the command to check
    * @tparam Command the type of the command to check
    */
  final case class Test[Command](id: String, cmd: Command) extends StateMachineMsg with NotInfluenceReceiveTimeout

  /**
    * Message for replying with the outcome of checking a command against a state machine. The command will only be tested
    * and the result will be discarded. The current state of the aggregate will not change.
    *
    * @param id    the persistence id
    * @param value either a rejection or the state that would be produced from the command evaluation
    * @tparam Rejection the type of rejection
    */
  final case class Tested[Rejection, State](id: String, value: Either[Rejection, State])
      extends StateMachineMsg
      with NotInfluenceReceiveTimeout

}
