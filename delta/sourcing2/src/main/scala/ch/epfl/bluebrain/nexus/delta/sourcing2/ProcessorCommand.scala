package ch.epfl.bluebrain.nexus.delta.sourcing2

import akka.actor.typed.ActorRef
import ch.epfl.bluebrain.nexus.delta.sourcing2.model.EntityId

import scala.concurrent.duration.FiniteDuration

sealed trait ProcessorCommand extends Product with Serializable

// format: off
object ProcessorCommand {

  sealed trait Request extends ProcessorCommand  {

    /**
     * @return the entity id
     */
    def id: EntityId
  }

  object Request {
    final case class Evaluate[Command](id: EntityId, command: Command, replyTo: ActorRef[Response.EvaluationResult]) extends Request
    final case class DryRun[Command](id: EntityId, command: Command, replyTo: ActorRef[Response.EvaluationResult]) extends Request
    final case class Stop(id: EntityId, replyTo: ActorRef[Response.StopResponse]) extends Request

    final case class GetState[State](id: EntityId, replyTo: ActorRef[Response.StateResponse[State]]) extends Request
  }

  private[sourcing2] sealed trait Start extends ProcessorCommand
  final private[sourcing2] case object InitialState extends Start
  final private[sourcing2] case class RecoveredState[State](value: State) extends Start
  final private[sourcing2] case object StartError extends Start

  /**
    * Message issued when when passivation is triggered
    */
  final private[sourcing2] case object Idle extends ProcessorCommand
}
// format: on

sealed trait Response extends Product with Serializable

object Response {
  final case class StateResponse[State](value: Option[State]) extends Response

  sealed trait StopResponse      extends Response
  final case object StopResponse extends StopResponse

  sealed trait EvaluationResult                                                extends Response with ProcessorCommand
  final case class EvaluationSuccess[Event, State](event: Event, state: State) extends EvaluationResult
  final case class EvaluationRejection[Rejection](value: Rejection)            extends EvaluationResult

  sealed trait EvaluationError                                                              extends EvaluationResult
  final case class EvaluationTimeout[Command](value: Command, timeoutAfter: FiniteDuration) extends EvaluationError
  final case class EvaluationFailure[Command](value: Command, message: Option[String])      extends EvaluationError
}
