package ch.epfl.bluebrain.nexus.sourcing.projections

import akka.actor.typed._
import akka.actor.typed.scaladsl.Behaviors
import cats.effect.concurrent.Ref
import cats.effect.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategy
import fs2.Stream
import fs2.concurrent.SignallingRef
import monix.bio.Task
import monix.execution.Scheduler
import retry.CatsEffect._
import retry.syntax.all._

import scala.concurrent.TimeoutException
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.util.{Failure, Success}

/**
  * The stream supervisor behaviors defined in order to created a typed Actor that manages a stream
  */
object StreamSupervisorBehavior {

  /**
    * Creates a behavior for a StreamSupervisor that manages the stream but does not store its state
    *
    * @param streamName    the embedded stream name
    * @param streamTask    the embedded stream
    * @param retryStrategy the strategy when the stream fails
    * @param onTerminate   Additional action when we stop the stream
    */
  private[projections] def stateless[A](
      streamName: String,
      streamTask: Task[Stream[Task, A]],
      retryStrategy: RetryStrategy[Throwable],
      onTerminate: Option[Task[Unit]] = None
  )(implicit scheduler: Scheduler): Behavior[SupervisorCommand] =
    behavior(streamName, streamTask, retryStrategy, StreamState.ignore[A], 100.millis, onTerminate)

  /**
    * Creates a behavior for a StreamSupervisor that manages the stream and stores its state
    *
    * @param streamName      the embedded stream name
    * @param streamTask      the embedded stream
    * @param retryStrategy   the strategy when the stream fails
    * @param state           the management of the stream state
    * @param stateReqTimeout the maximum amount of time to wait to retrieve the current state
    * @param onTerminate     Additional action when we stop the stream
    */
  private[projections] def stateful[A, S](
      streamName: String,
      streamTask: Task[Stream[Task, A]],
      retryStrategy: RetryStrategy[Throwable],
      state: StreamState[A, S],
      stateReqTimeout: FiniteDuration,
      onTerminate: Option[Task[Unit]] = None
  )(implicit scheduler: Scheduler): Behavior[SupervisorCommand] =
    behavior(streamName, streamTask, retryStrategy, state, stateReqTimeout, onTerminate)

  private def behavior[A, S](
      streamName: String,
      streamTask: Task[Stream[Task, A]],
      retryStrategy: RetryStrategy[Throwable],
      state: StreamState[A, S],
      stateReqTimeout: FiniteDuration,
      onTerminate: Option[Task[Unit]]
  )(implicit scheduler: Scheduler): Behavior[SupervisorCommand] =
    Behaviors.setup[SupervisorCommand] { context =>
      import context._
      import retryStrategy._

      // Adds an interrupter to the stream and start its evaluation
      def start(): Behavior[SupervisorCommand] = {
        log.info("Starting the stream for StreamSupervisor {}", streamName)
        val interrupter = SignallingRef[Task, Boolean](false).toIO.unsafeRunSync()
        val program     = streamTask
          .flatMap { stream =>
            stream
              .evalMap(entry => state.update(entry).as(entry))
              .interruptWhen(interrupter)
              .onFinalize(onTerminate.getOrElse(Task.unit))
              .compile
              .drain
          }
          .retryingOnSomeErrors(retryWhen)

        // When the streams ends, we stop the actor
        (program >> Task.delay(self ! Stop)).runAsyncAndForget

        running(interrupter)
      }

      def interruptStream(interrupter: SignallingRef[Task, Boolean]): Unit =
        interrupter.set(true).toIO.unsafeRunSync()

      def running(interrupter: SignallingRef[Task, Boolean]): Behavior[SupervisorCommand] =
        Behaviors
          .receiveMessage[SupervisorCommand] {
            case Stop =>
              log.info("Stop has been requested for {}, stopping the stream", streamName)
              interruptStream(interrupter)
              Behaviors.stopped

            case FetchState(replyTo) =>
              context.pipeToSelf(state.fetch.timeoutWith(stateReqTimeout, new TimeoutException()).runToFuture) {
                case Success(value)               => StateFetched(value, replyTo)
                case Failure(_: TimeoutException) =>
                  context.log.error("Timed out while fetching state from stream '{}'", streamName)
                  StateFetchFailure(Some(s"Timed out while fetching state from stream '$streamName'"), replyTo)
                case Failure(th)                  =>
                  context.log.error(s"Error while fetching state from stream '{}'", streamName)
                  StateFetchFailure(Option(th.getMessage), replyTo)
              }
              Behaviors.same

            case StateFetchFailure(msg, replyTo) =>
              replyTo ! StateReplyError(msg)
              Behaviors.same

            case StateFetched(value, replyTo) =>
              replyTo ! StateReply(value)
              Behaviors.same
          }
          .receiveSignal {
            case (_, PostStop)   =>
              log.info(s"Stopped the actor {}, we stop the stream", streamName)
              interruptStream(interrupter)
              Behaviors.same
            case (_, PreRestart) =>
              log.info(s"Restarting the actor {}, we stop the stream", streamName)
              interruptStream(interrupter)
              Behaviors.same
          }

      start()
    }

  trait StreamState[A, State] {
    def fetch: Task[State]
    def update(entry: A): Task[Unit]
  }

  object StreamState {

    /**
      * Do not record any state
      */
    def ignore[A]: StreamState[A, Unit] = new StreamState[A, Unit] {
      override def fetch: Task[Unit]            = Task.unit
      override def update(entry: A): Task[Unit] = Task.unit
    }

    /**
      * Record the state on a concurrent ''ref''.
      *
      * @param initial the initial state
      * @param next    the function that generates a next state given a stream entry and the current state
      */
    def record[A, State](initial: State, next: (State, A) => State): Task[StreamState[A, State]] =
      Ref.of[Task, State](initial).map { ref =>
        new StreamState[A, State] {
          override def fetch: Task[State]           = ref.get
          override def update(entry: A): Task[Unit] = ref.update(next(_, entry))
        }
      }
  }

  /**
    * Command that can be sent to the stream supervisor
    */
  sealed trait SupervisorCommand extends Product with Serializable

  /**
    * Command that stops the stream handled by the supervisor
    */
  final case object Stop extends SupervisorCommand

  /**
    * Command that requests the current state
    */
  final case class FetchState(replyTo: ActorRef[SupervisorReply]) extends SupervisorCommand

  final private case class StateFetched[A](value: A, replyTo: ActorRef[SupervisorReply]) extends SupervisorCommand

  final private case class StateFetchFailure[A](message: Option[String], replyTo: ActorRef[SupervisorReply])
      extends SupervisorCommand

  /**
    * Response that can be sent from the stream supervisor
    */
  sealed trait SupervisorReply extends Product with Serializable

  /**
    * Successful response to a [[FetchState]] command
    */
  final case class StateReply[A](value: A) extends SupervisorReply

  /**
    * Failure response to a [[FetchState]] command
    */
  final case class StateReplyError(message: Option[String]) extends Exception with SupervisorReply

}
