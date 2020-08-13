package ch.epfl.bluebrain.nexus.sourcingnew.processor

import akka.actor.typed.ActorRef
import akka.routing.ConsistentHashingRouter.ConsistentHashable

import scala.concurrent.duration.FiniteDuration

/**
  * Command used in [[EventSourceProcessor]]
  */
sealed trait ProcessorCommand extends Product with Serializable

/**
  * Event sent when the [[EventSourceProcessor]] has been idling for too long
  * according to a defined [[StopStrategy]]
  */
case object Idle extends ProcessorCommand

/**
  * Command sent to the [[EventSourceProcessor]] by an other component.
  * The id has to match the entity id of the instance of the eventProcessor
  *
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
sealed trait ReadonlyCommand extends EventSourceCommand
final case class RequestState[State](id: String, replyTo: ActorRef[State])  extends ReadonlyCommand
final case class RequestLastSeqNr(id: String, replyTo: ActorRef[GetLastSeqNr])  extends ReadonlyCommand

/**
  * Internal event sent by the [[EventSourceProcessor]] to its state actor
  * @param id
  * @param event
  * @tparam Event
  */
final case class Append[Event] private [processor](id: String, event: Event) extends EventSourceCommand

/**
  * Defines a command to evaluate the evaluateCommand giving a [[EvaluationResult]]
  * which is sent back to the replyTo
  *
  * @param id
  * @param evaluateCommand
  * @param replyTo
  * @tparam EvaluateCommand
  */
//TODO: Add a FlatMap or Monad implementation ?
final case class Evaluate[EvaluateCommand](id: String, evaluateCommand: EvaluateCommand, replyTo: ActorRef[EvaluationResult]) extends InputCommand

/**
  * Same thing as [[Evaluate]] but the result is never appended to the event log
  * @param id
  * @param evaluateCommand
  * @param replyTo
  * @tparam EvaluateCommand
  */
final case class DryRun[EvaluateCommand](id: String, evaluateCommand: EvaluateCommand, replyTo: ActorRef[DryRunResult]) extends InputCommand

// Evaluation results
sealed trait RunResult extends ProcessorCommand
sealed trait EvaluationResult extends RunResult

/**
  * Describes the event and the state computed when an an [[Evaluate]] or a [[DryRun]]
  * has been successfully evaluated
  *
  * @param value
  * @param state
  * @tparam Event
  * @tparam State
  */
final case class EvaluationSuccess[Event, State](value: Event, state: State) extends EvaluationResult

/**
  * Rejection that occured after running an an [[Evaluate]] or a [[DryRun]]
  * @param value
  * @tparam Rejection
  */
final case class EvaluationRejection[Rejection](value: Rejection) extends EvaluationResult

/**
  * Error that occured after running an [[Evaluate]] or a [[DryRun]]
  */
abstract class EvaluationError extends Exception with EvaluationResult
final case class EvaluationCommandTimeout[EvaluateCommand](value: EvaluateCommand, timeoutAfter: FiniteDuration) extends EvaluationError
final case class EvaluationCommandError[EvaluateCommand](value: EvaluateCommand, message: Option[String]) extends EvaluationError

/**
  * Result of a [[DryRun]]
  * @param evaluateResult
  */
final case class DryRunResult(evaluateResult: EvaluationResult) extends RunResult

// Replies
sealed trait AggregateReply
final case class GetLastSeqNr(value: Long) extends AggregateReply