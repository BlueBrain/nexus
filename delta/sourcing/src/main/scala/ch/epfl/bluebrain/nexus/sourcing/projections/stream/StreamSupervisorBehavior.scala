package ch.epfl.bluebrain.nexus.sourcing.projections.stream

import akka.actor.typed._
import akka.actor.typed.scaladsl.Behaviors
import cats.effect.concurrent.Ref
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategy
import fs2.Stream
import fs2.concurrent.SignallingRef
import monix.bio.Task
import monix.execution.Scheduler
import retry.CatsEffect._
import retry.syntax.all._
import scala.concurrent.duration._

/**
  * The stream supervisor behaviors defined in order to created a typed Actor that manages a stream
  */
object StreamSupervisorBehavior {

  /**
    * Creates a behavior for a StreamSupervisor that manages the stream
    *
    * @param streamName    the embedded stream name
    * @param streamTask    the embedded stream
    * @param retryStrategy the strategy when the stream fails
    * @param onTerminate   Additional action when we stop the stream
    */
  private[projections] def apply[A](
      streamName: String,
      streamTask: Task[Stream[Task, A]],
      retryStrategy: RetryStrategy[Throwable],
      onTerminate: Task[Unit],
      terminated: Ref[Task, Boolean]
  )(implicit scheduler: Scheduler): Behavior[SupervisorCommand] =
    Behaviors.setup[SupervisorCommand] { context =>
      import context._
      import retryStrategy._

      // Adds an interrupter to the stream and start its evaluation
      def start(): Behavior[SupervisorCommand] = {
        log.info("Starting the stream for StreamSupervisor {}", streamName)
        val interrupter = SignallingRef[Task, Boolean](false).runSyncUnsafe()
        val program     = streamTask
          .flatMap { stream =>
            stream
              .interruptWhen(interrupter)
              .onFinalize(onTerminate >> terminated.set(true))
              .compile
              .drain
          }
          .retryingOnSomeErrors(retryWhen)

        // When the streams ends, we stop the actor
        (program >> Task.delay(self ! Stop())).runAsyncAndForget

        running(interrupter)
      }

      def interruptStream(interrupter: SignallingRef[Task, Boolean]): Unit =
        interrupter.set(true).runSyncUnsafe()

      def running(interrupter: SignallingRef[Task, Boolean]): Behavior[SupervisorCommand] =
        Behaviors
          .receiveMessage[SupervisorCommand] { case Stop(reply) =>
            log.info("Stop has been requested for {}, stopping the stream", streamName)
            interruptStream(interrupter)
            terminated.get.restartUntil(_ == true).timeout(5.seconds).runSyncUnsafe()
            reply.foreach(_ ! ())
            Behaviors.stopped
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

  /**
    * Command that can be sent to the stream supervisor
    */
  sealed trait SupervisorCommand extends Product with Serializable

  /**
    * Command that stops the stream handled by the supervisor
    */
  final case class Stop(reply: Option[ActorRef[Unit]] = None) extends SupervisorCommand

}
