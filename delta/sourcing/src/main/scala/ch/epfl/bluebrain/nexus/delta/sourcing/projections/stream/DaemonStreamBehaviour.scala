package ch.epfl.bluebrain.nexus.delta.sourcing.projections.stream

import akka.actor.typed._
import akka.actor.typed.scaladsl.Behaviors
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategy
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import com.typesafe.scalalogging.Logger
import fs2.Stream
import monix.bio.{Task, UIO}
import monix.execution.Scheduler

import java.util.UUID

/**
  * Behavior to define and supervise a running stream in background
  */
object DaemonStreamBehaviour {

  implicit private val logger: Logger = Logger[DaemonStreamBehaviour.type]

  /**
    * Creates a behavior for a StreamSupervisor that manages the stream
    *
    * @param streamName    the embedded stream name
    * @param streamTask    the embedded stream
    * @param retryStrategy the strategy when the stream fails
    */
  private[projections] def apply[A](
      streamName: String,
      streamTask: Task[Stream[Task, A]],
      retryStrategy: RetryStrategy[Throwable]
  )(implicit uuidF: UUIDF, s: Scheduler): Behavior[SupervisorCommand] =
    Behaviors.setup[SupervisorCommand] { context =>
      import context._

      def onFinalize(uuid: UUID) = UIO.delay(self ! StreamStopped(uuid))

      // Adds an interrupter to the stream and start its evaluation
      def start(): Behavior[SupervisorCommand] = {
        logger.info("Starting the stream for StreamSupervisor {}", streamName)
        val switch = StreamSwitch
          .run(
            streamName,
            streamTask,
            retryStrategy,
            onCancel = onFinalize,
            onFinalize = onFinalize
          )
          .runSyncUnsafe()

        running(switch)
      }

      def running(switch: StreamSwitch): Behavior[SupervisorCommand] =
        Behaviors
          .receiveMessage[SupervisorCommand] {
            case StreamStopped(uuid) if switch.uuid == uuid =>
              logger.info("The current stream {} just finished, stopping the actor", streamName)
              Behaviors.stopped
            case _: StreamStopped                           =>
              logger.debug("A previous stream stopped, carrying on with the current one", streamName)
              Behaviors.same
            case Stop(reply)                                =>
              logger.info("Stop has been requested for {}, stopping the stream", streamName)
              switch.stop.runAsyncAndForget
              reply.foreach(_ ! ())
              Behaviors.stopped
          }
          .receiveSignal {
            case (_, PostStop)   =>
              logger.info(s"Stopped the actor {}, we stop the stream", streamName)
              switch.stop.runAsyncAndForget
              Behaviors.same
            case (_, PreRestart) =>
              logger.info(s"Restarting the actor {}, we stop the stream", streamName)
              switch.stop.runAsyncAndForget
              Behaviors.same
          }

      start()
    }

  /**
    * Command that can be sent to the stream supervisor
    */
  sealed trait SupervisorCommand extends Product with Serializable

  final private case class StreamStopped(uuid: UUID) extends SupervisorCommand

  /**
    * Command that stops the stream handled by the supervisor
    */
  final case class Stop(reply: Option[ActorRef[Unit]] = None) extends SupervisorCommand

}
