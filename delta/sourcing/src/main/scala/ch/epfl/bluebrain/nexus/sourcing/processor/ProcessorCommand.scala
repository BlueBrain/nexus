package ch.epfl.bluebrain.nexus.sourcing.processor

import akka.actor.typed.ActorRef
import akka.routing.ConsistentHashingRouter.ConsistentHashable
import ch.epfl.bluebrain.nexus.sourcing.processor.AggregateReply.{LastSeqNr, StateReply}

import scala.concurrent.duration.FiniteDuration

/**
  * Command used in [[EventSourceProcessor]]
  */
sealed trait ProcessorCommand extends Product with Serializable

object ProcessorCommand {

  /**
    * Event sent when the [[EventSourceProcessor]] has been idling for too long
    * according to a defined [[StopStrategy]]
    */
  case object Idle extends ProcessorCommand

  /**
    * Command sent to the [[EventSourceProcessor]] by an other component.
    * The id has to match the entity id of the instance of the eventProcessor
    */
  sealed trait InputCommand extends ConsistentHashable with ProcessorCommand {

    /**
      * @return the persistence id
      */
    def id: String

    override def consistentHashKey: String = id
  }

  /**
    * Command that can be forwarded from the [[EventSourceProcessor]] to its child actor stateActor
    */
  sealed trait EventSourceCommand extends InputCommand

  /**
    * Read only commands that don't need to be stashed when a [[Evaluate]] is running
    */
  sealed trait ReadonlyCommand                                                               extends EventSourceCommand
  final case class RequestState[State](id: String, replyTo: ActorRef[StateReply[State]])     extends ReadonlyCommand
  final case class RequestLastSeqNr(id: String, replyTo: ActorRef[LastSeqNr])                extends ReadonlyCommand
  final private[processor] case class RequestStateInternal[State](
      id: String,
      replyTo: ActorRef[ResponseStateInternal[State]]
  )                                                                                          extends ReadonlyCommand
  final private[processor] case class ResponseStateInternal[State](id: String, value: State) extends ReadonlyCommand

  /**
    * Internal event sent by the [[EventSourceProcessor]] to its state actor
    * @param event the event to persist
    */
  final case class Append[Event] private[processor] (id: String, event: Event) extends EventSourceCommand

  /**
    * Defines a command to evaluate the command giving a [[EvaluationResult]]
    * which is sent back to the replyTo
    *
    * @param command the command to evaluate
    * @param replyTo the actor to send the result to
    */
  final case class Evaluate[Command](id: String, command: Command, replyTo: ActorRef[EvaluationResult])
      extends InputCommand

  /**
    * Same thing as [[Evaluate]] but the result is never appended to the event log
    * @param command the command to test
    * @param replyTo the actor to send the result to
    */
  final case class DryRun[Command](id: String, command: Command, replyTo: ActorRef[DryRunResult]) extends InputCommand

  // Evaluation results
  sealed trait RunResult        extends ProcessorCommand
  sealed trait EvaluationResult extends RunResult

  /**
    * Describes the event and the state computed when an an [[Evaluate]] or a [[DryRun]]
    * has been successfully evaluated
    *
    * @param event the generated event
    * @param state the new state
    */
  final case class EvaluationSuccess[Event, State](event: Event, state: State) extends EvaluationResult

  /**
    * Rejection that occured after running an an [[Evaluate]] or a [[DryRun]]
    * @param value the rejection
    */
  final case class EvaluationRejection[Rejection](value: Rejection) extends EvaluationResult

  /**
    * Error that occured after running an [[Evaluate]] or a [[DryRun]]
    */
  abstract class EvaluationError                                                            extends Exception with EvaluationResult
  final case class EvaluationCommandTimeout[Command](value: Command, timeoutAfter: FiniteDuration)
      extends EvaluationError
  final case class EvaluationCommandError[Command](value: Command, message: Option[String]) extends EvaluationError

  /**
    * Result of a [[DryRun]]
    * @param result the result we got, can be a success / a rejection / an error
    */
  final case class DryRunResult(result: EvaluationResult) extends RunResult

}

// Replies
sealed trait AggregateReply extends Product with Serializable

object AggregateReply {
  final case class LastSeqNr(value: Long)          extends AggregateReply
  final case class StateReply[State](value: State) extends AggregateReply
}
