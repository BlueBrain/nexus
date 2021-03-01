package ch.epfl.bluebrain.nexus.delta.sourcing.projections.stream

import akka.actor.typed._
import akka.actor.typed.scaladsl.Behaviors
import cats.effect.ExitCase
import cats.effect.concurrent.Ref
import ch.epfl.bluebrain.nexus.delta.kernel.syntax._
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategy
import com.typesafe.scalalogging.Logger
import fs2.Stream
import fs2.concurrent.SignallingRef
import monix.bio.{Task, UIO}
import monix.execution.Scheduler
import retry.CatsEffect._
import retry.syntax.all._

import scala.concurrent.duration._

/**
  * The stream supervisor behaviors defined in order to created a typed Actor that manages a stream
  */
object StreamSupervisorBehavior {

  implicit private val logger: Logger = Logger[StreamSupervisorBehavior.type]

  /**
    * Creates a behavior for a StreamSupervisor that manages the stream
    *
    * @param streamName    the embedded stream name
    * @param streamTask    the embedded stream
    * @param retryStrategy the strategy when the stream fails
    * @param onStreamFinalize   Additional action when we stop the stream
    */
  private[projections] def apply[A](
      streamName: String,
      streamTask: Task[Stream[Task, A]],
      retryStrategy: RetryStrategy[Throwable],
      onStreamFinalize: UIO[Unit],
      terminated: Ref[Task, Boolean]
  )(implicit scheduler: Scheduler): Behavior[SupervisorCommand] =
    Behaviors.setup[SupervisorCommand] { context =>
      import context._
      import retryStrategy._

      // Adds an interrupter to the stream and start its evaluation
      def start(): Behavior[SupervisorCommand] = {
        logger.info("Starting the stream for StreamSupervisor {}", streamName)
        val interrupter = SignallingRef[Task, Boolean](false).runSyncUnsafe()
        val program     = streamTask
          .flatMap { stream =>
            stream
              .interruptWhen(interrupter)
              .onFinalizeCase {
                case ExitCase.Completed =>
                  UIO.delay(logger.debug(s"Stream $streamName has been successfully completed.")) >> onStreamFinalize
                case ExitCase.Error(e)  =>
                  UIO.delay(logger.error(s"Stream $streamName events has failed.", e)) >> onStreamFinalize
                case ExitCase.Canceled  =>
                  UIO.delay(logger.warn(s"Stream $streamName got cancelled.")) >> onStreamFinalize
              }
              .compile
              .drain
          }
          .retryingOnSomeErrors(retryWhen)

        def onExit(outcome: String): UIO[Unit] =
          terminated.set(true).logAndDiscardErrors(s"updating the termination Ref of the stream '$streamName'") >>
            UIO.delay(logger.info("Stopping actor for stream {} after {}...", streamName, outcome)) >>
            UIO.delay(self ! Stop())

        // When the streams ends, we stop the actor
        program
          .doOnCancel(onExit("cancellation"))
          .doOnFinish {
            case Some(cause) => onExit(s"error on $cause")
            case None        => onExit("completion")
          }
          .runAsyncAndForget

        running(interrupter)
      }

      def interruptStream(interrupter: SignallingRef[Task, Boolean]): Unit =
        interrupter.set(true).runSyncUnsafe()

      def running(interrupter: SignallingRef[Task, Boolean]): Behavior[SupervisorCommand] =
        Behaviors
          .receiveMessage[SupervisorCommand] { case Stop(reply) =>
            logger.info("Stop has been requested for {}, stopping the stream", streamName)
            interruptStream(interrupter)
            terminated.get.restartUntil(_ == true).timeout(5.seconds).runSyncUnsafe()
            reply.foreach(_ ! ())
            Behaviors.stopped
          }
          .receiveSignal {
            case (_, PostStop)   =>
              logger.info(s"Stopped the actor {}, we stop the stream", streamName)
              interruptStream(interrupter)
              Behaviors.same
            case (_, PreRestart) =>
              logger.info(s"Restarting the actor {}, we stop the stream", streamName)
              interruptStream(interrupter)
              Behaviors.same
          }

      start()
    }

  /**
    * Command that can be sent to the stream supervisor
    */
  sealed trait SupervisorCommand extends Product with Serializable

  /**
    * Command that stops the stream handled by the supervisor
    */
  final case class Stop(reply: Option[ActorRef[Unit]] = None) extends SupervisorCommand

}
