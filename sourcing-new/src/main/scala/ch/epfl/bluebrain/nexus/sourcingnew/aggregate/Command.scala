package ch.epfl.bluebrain.nexus.sourcingnew.aggregate

import akka.actor.typed.ActorRef
import akka.routing.ConsistentHashingRouter.ConsistentHashable

import scala.concurrent.duration.FiniteDuration

sealed trait Command extends Product with Serializable

case object Idle extends Command

// Read commands
sealed trait InputCommand extends ConsistentHashable with Command {

  /**
    * @return the persistence id
    */
  def id: String

  override def consistentHashKey: String = id
}
sealed trait EventSourceCommand extends InputCommand
sealed trait ReadonlyCommand extends EventSourceCommand
final case class RequestState[State](id: String, replyTo: ActorRef[State])  extends ReadonlyCommand
final case class RequestLastSeqNr(id: String, replyTo: ActorRef[GetLastSeqNr])  extends ReadonlyCommand
final case class Snapshot(id: String) extends ReadonlyCommand

// Write command
final case class Append[Event](id: String, event: Event) extends EventSourceCommand

//TODO: Add a FlatMap or Monad implementation ?
final case class Evaluate[EvaluateCommand](id: String, evaluateCommand: EvaluateCommand, replyTo: ActorRef[EvaluationResult]) extends InputCommand
final case class DryRun[EvaluateCommand](id: String, evaluateCommand: EvaluateCommand, replyTo: ActorRef[DryRunResult]) extends InputCommand

// Evaluation results
sealed trait RunResult extends Command
sealed trait EvaluationResult extends RunResult
final case class EvaluationSuccess[Event, State](value: Event, state: State) extends EvaluationResult
final case class EvaluationRejection[Rejection](value: Rejection) extends EvaluationResult

abstract class EvaluationError extends Exception with EvaluationResult
final case class EvaluationCommandTimeout[EvaluateCommand](value: EvaluateCommand, timeoutAfter: FiniteDuration) extends EvaluationError
final case class EvaluationCommandError[EvaluateCommand](value: EvaluateCommand, message: Option[String]) extends EvaluationError

final case class DryRunResult(evaluateResult: EvaluationResult) extends RunResult

// Replies
sealed trait AggregateReply
final case class GetLastSeqNr(value: Long) extends AggregateReply