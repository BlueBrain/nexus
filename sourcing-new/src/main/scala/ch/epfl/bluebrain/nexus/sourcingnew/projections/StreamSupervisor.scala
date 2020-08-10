package ch.epfl.bluebrain.nexus.sourcingnew.projections

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{Behavior, PostStop, PreRestart}
import cats.effect.syntax.all._
import cats.effect.{ConcurrentEffect, Timer}
import cats.implicits._
import ch.epfl.bluebrain.nexus.sourcingnew.RetryStrategy
import fs2.Stream
import fs2.concurrent.SignallingRef
import retry.CatsEffect._
import retry.syntax.all._

object StreamSupervisor {

  sealed trait SupervisorCommand

  /**
    * Stops the stream handled by the supervisor
    */
  case object Stop extends SupervisorCommand

  /**
    * Creates a StreamSupervisor and start the embedded stream
    * @param streamF the embedded stream
    * @param retryStrategy the strategy when the stream fails
    * @param onTerminate Additional action when we stop the stream
    * @param F
    * @tparam F
    * @tparam A
    * @return
    */
  def behavior[F[_]: Timer, A](streamF: F[Stream[F, A]],
                               retryStrategy: RetryStrategy[F],
                               onTerminate: Option[F[Unit]] = None)
                              (implicit F: ConcurrentEffect[F]): Behavior[SupervisorCommand] =
    Behaviors.setup[SupervisorCommand] { context =>
      import context._
      import retryStrategy._

      // Adds an interrupter to the stream and start its evaluation
      def start(): Behavior[SupervisorCommand] = {
        log.info("Starting the stream for StreamSupervisor {}", self.path.name)
        val interrupter = SignallingRef[F, Boolean](false).toIO.unsafeRunSync()

        val program = streamF.flatMap { _.interruptWhen(interrupter)
          .onFinalize {
            // When the streams ends, we can do some cleanup
            onTerminate.getOrElse { F.unit }
          }.compile.drain.toIO.to[F]
        }.retryingOnSomeErrors(retryWhen)

        // When the streams ends, we stop the actor
        (program >> F.delay { self ! Stop }).toIO.unsafeRunAsyncAndForget()

        running(interrupter)
      }

      def interruptStream(interrupter: SignallingRef[F, Boolean]): Unit =
        interrupter.set(true).toIO.unsafeRunSync

      def running(interrupter: SignallingRef[F, Boolean]): Behavior[SupervisorCommand] =
        Behaviors.receiveMessage[SupervisorCommand] {
          case Stop =>
            log.debug("Stop has been requested, stopping the stream", self.path.name)
            interruptStream(interrupter)
            Behaviors.stopped
        }.receiveSignal {
          case (_, PostStop)  =>
            log.info(s"Stopped the actor, we stop the stream")
            interruptStream(interrupter)
            Behaviors.same
          case (_, PreRestart)  =>
            log.info(s"Restarting the actor, we stop the stream")
            interruptStream(interrupter)
            Behaviors.same
        }

      start()
    }

}

