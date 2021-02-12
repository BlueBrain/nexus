package ch.epfl.bluebrain.nexus.sourcing.processor

import akka.actor.typed.ActorRef

import scala.concurrent.duration.FiniteDuration
/*
scalafmt: {
  style = defaultWithAlign
  maxColumn = 150
}
 */
/** Incoming messages to [[EventSourceProcessor]] actor
  */
sealed trait ProcessorCommand extends Product with Serializable

object ProcessorCommand {

  /** Incoming messages from the outside to [[EventSourceProcessor]] actor
    */
  sealed trait AggregateRequest extends ProcessorCommand {

    /** @return the persistence id
      */
    def id: String
  }

  object AggregateRequest {
    final case class Evaluate[Command](id: String, command: Command, replyTo: ActorRef[AggregateResponse.EvaluationResult]) extends AggregateRequest
    final case class DryRun[Command](id: String, command: Command, replyTo: ActorRef[AggregateResponse.EvaluationResult])   extends AggregateRequest

    sealed trait ReadOnlyRequest                                                                                extends AggregateRequest
    final case class RequestState[State](id: String, replyTo: ActorRef[AggregateResponse.StateResponse[State]]) extends ReadOnlyRequest
    final case class RequestLastSeqNr(id: String, replyTo: ActorRef[AggregateResponse.LastSeqNr])               extends ReadOnlyRequest
  }

  /** Message issued when when passivation is triggered
    */
  final private[processor] case object Idle extends ProcessorCommand

  /** Messages issued from within the [[EventSourceProcessor]] as an evaluation result
    */
  sealed private[processor] trait EvaluationResultInternal extends ProcessorCommand
  private[processor] object EvaluationResultInternal {
    final case class EvaluationSuccess[Event, State](event: Event, state: State) extends EvaluationResultInternal
    final case class EvaluationRejection[Rejection](value: Rejection)            extends EvaluationResultInternal

    sealed trait EvaluationError                                                              extends Exception with EvaluationResultInternal
    final case class EvaluationTimeout[Command](value: Command, timeoutAfter: FiniteDuration) extends EvaluationError
    final case class EvaluationFailure[Command](value: Command, message: Option[String])      extends EvaluationError
  }
}

/** Incoming messages from within the [[EventSourceProcessor]] to child actor
  */
sealed private[processor] trait ChildActorRequest extends Product with Serializable
private[processor] object ChildActorRequest {
  final case class Ask[Question](question: Question, replyTo: ActorRef[AskResponse]) extends ChildActorRequest
  final case class RequestLastSeqNr(replyTo: ActorRef[AggregateResponse.LastSeqNr])                  extends ChildActorRequest
  final case class RequestState[State](replyTo: ActorRef[AggregateResponse.StateResponse[State]])    extends ChildActorRequest
  final case object RequestStateInternal                                                             extends ChildActorRequest
  final case class Append[Event](event: Event)                                                       extends ChildActorRequest

  sealed trait AskResponse extends ChildActorRequest {
    def replyTo: ActorRef[AskResponse]
  }
  object AskResponse {
    final case class AskSuccess[Answer](answer: Answer, replyTo: ActorRef[AskResponse])        extends AskResponse
    final case class AskRejection[Rejection](value: Rejection, replyTo: ActorRef[AskResponse]) extends AskResponse

    sealed trait AskError                                                                     extends Exception with AskResponse
    final case class AskTimeout[Question](question: Question, timeoutAfter: FiniteDuration, replyTo: ActorRef[AskResponse])   extends AskError
    final case class AskFailure[Question](question: Question, message: Option[String], replyTo: ActorRef[AskResponse]) extends AskError
  }
}

/** Outgoing messages from child actor to the [[EventSourceProcessor]] actor
  */
sealed private[processor] trait ChildActorResponse extends ProcessorCommand
private[processor] object ChildActorResponse {
  final case class AppendResult[Event, State](event: Event, state: State) extends ChildActorResponse
  final case class StateResponseInternal[State](value: State)             extends ChildActorResponse
}


/** Replies from [[EventSourceProcessor]] actor to the outside
  */
sealed trait AggregateResponse extends Product with Serializable

object AggregateResponse {
  final case class LastSeqNr(value: Long)             extends AggregateResponse
  final case class StateResponse[State](value: State) extends AggregateResponse

  sealed trait EvaluationResult                                                extends AggregateResponse
  final case class EvaluationSuccess[Event, State](event: Event, state: State) extends EvaluationResult
  final case class EvaluationRejection[Rejection](value: Rejection)            extends EvaluationResult

  sealed trait EvaluationError                                                              extends Exception with EvaluationResult
  final case class EvaluationTimeout[Command](value: Command, timeoutAfter: FiniteDuration) extends EvaluationError
  final case class EvaluationFailure[Command](value: Command, message: Option[String])      extends EvaluationError
}
