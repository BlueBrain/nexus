package ch.epfl.bluebrain.nexus.sourcingnew.aggregate

import akka.actor.typed.ActorRef
import akka.routing.ConsistentHashingRouter.ConsistentHashable

import scala.concurrent.duration.FiniteDuration

sealed trait Command extends Product with Serializable

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

final case class Evaluate[EvaluateCommand](id: String, evaluateCommand: EvaluateCommand, replyTo: ActorRef[EvaluateResult]) extends InputCommand
final case class DryRun[EvaluateCommand](id: String, evaluateCommand: EvaluateCommand, replyTo: ActorRef[EvaluateResult]) extends InputCommand

// Evaluation results
sealed trait EvaluateResult extends Command
final case class EvaluateSuccess[Event, State](value: Event, state: State) extends EvaluateResult
final case class EvaluateRejection[Rejection](value: Rejection) extends EvaluateResult
sealed trait EvaluateError extends EvaluateResult
final case class EvaluateCommandTimeout[EvaluateCommand](value: EvaluateCommand, timeoutAfter: FiniteDuration) extends EvaluateError
final case class EvaluateCommandError[EvaluateCommand](value: EvaluateCommand, message: Option[String]) extends EvaluateError

final case class DryRunResult(evaluateResult: EvaluateResult) extends EvaluateResult

// Replies
sealed trait AggregateReply
final case class GetLastSeqNr(value: Long) extends AggregateReply