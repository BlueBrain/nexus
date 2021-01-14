package ch.epfl.bluebrain.nexus.sourcing.projections

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, ActorSystem, Behavior, PostStop, PreRestart, SupervisorStrategy}
import akka.cluster.typed.{ClusterSingleton, SingletonActor}
import cats.effect.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategy
import fs2.Stream
import fs2.concurrent.SignallingRef
import monix.bio.Task
import monix.execution.Scheduler
import retry.CatsEffect._
import retry.syntax.all._

/**
  * Allows to supervise a stream through an actor
  */
object StreamSupervisor {

  /**
    * Command that can be sent to the stream supervisor
    */
  sealed trait SupervisorCommand extends Product with Serializable

  /**
    * Stops the stream handled by the supervisor
    */
  case object Stop extends SupervisorCommand

  /**
    * Runs a StreamSupervisor as a [[ClusterSingleton]].
    *
    * @param name          the unique name for the singleton
    * @param streamTask    the embedded stream
    * @param retryStrategy the strategy when the stream fails
    * @param onTerminate   Additional action when we stop the stream
    */
  def runAsSingleton[A](
      name: String,
      streamTask: Task[Stream[Task, A]],
      retryStrategy: RetryStrategy[Throwable],
      onTerminate: Option[Task[Unit]] = None
  )(implicit as: ActorSystem[Nothing], scheduler: Scheduler): ActorRef[SupervisorCommand] = {
    val singletonManager = ClusterSingleton(as)
    val behavior         = StreamSupervisor.behavior(streamTask, retryStrategy, onTerminate)
    singletonManager.init {
      SingletonActor(Behaviors.supervise(behavior).onFailure[Exception](SupervisorStrategy.restart), name)
    }
  }

  /**
    * Creates a StreamSupervisor and start the embedded stream
    *
    * @param streamTask    the embedded stream
    * @param retryStrategy the strategy when the stream fails
    * @param onTerminate   Additional action when we stop the stream
    */
  def behavior[A](
      streamTask: Task[Stream[Task, A]],
      retryStrategy: RetryStrategy[Throwable],
      onTerminate: Option[Task[Unit]] = None
  )(implicit scheduler: Scheduler): Behavior[SupervisorCommand] =
    Behaviors.setup[SupervisorCommand] { context =>
      import context._
      import retryStrategy._

      // Adds an interrupter to the stream and start its evaluation
      def start(): Behavior[SupervisorCommand] = {
        log.info("Starting the stream for StreamSupervisor {}", self.path.name)
        val interrupter = SignallingRef[Task, Boolean](false).toIO.unsafeRunSync()

        val program = streamTask
          .flatMap {
            _.interruptWhen(interrupter)
              .onFinalize {
                // When the streams ends, we can do some cleanup
                onTerminate.getOrElse { Task.unit }
              }
              .compile
              .drain
          }
          .retryingOnSomeErrors(retryWhen)

        // When the streams ends, we stop the actor
        (program >> Task.delay { self ! Stop }).runAsyncAndForget

        running(interrupter)
      }

      def interruptStream(interrupter: SignallingRef[Task, Boolean]): Unit =
        interrupter.set(true).toIO.unsafeRunSync()

      def running(interrupter: SignallingRef[Task, Boolean]): Behavior[SupervisorCommand] =
        Behaviors
          .receiveMessage[SupervisorCommand] { case Stop =>
            log.debug("Stop has been requested, stopping the stream", self.path.name)
            interruptStream(interrupter)
            Behaviors.stopped
          }
          .receiveSignal {
            case (_, PostStop)   =>
              log.info(s"Stopped the actor, we stop the stream")
              interruptStream(interrupter)
              Behaviors.same
            case (_, PreRestart) =>
              log.info(s"Restarting the actor, we stop the stream")
              interruptStream(interrupter)
              Behaviors.same
          }

      start()
    }

}
