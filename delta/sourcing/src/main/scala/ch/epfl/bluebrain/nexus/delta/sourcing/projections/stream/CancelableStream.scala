package ch.epfl.bluebrain.nexus.delta.sourcing.projections.stream

import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategy
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.sourcing.syntax._
import com.typesafe.scalalogging.Logger
import fs2.concurrent.SignallingRef
import fs2.{Pipe, Stream}
import monix.bio.{Task, UIO}
import monix.execution.Scheduler
import retry.syntax.all._

import java.util.UUID

class CancelableStream[A, S](switch: StreamSwitch[S], value: Stream[Task, A]) { self =>
  import CancelableStream.logger

  /**
    * Stops the stream
    */
  def stop(message: S): Task[Unit] = switch.stop(message)

  def through[B](pipe: Pipe[Task, A, B]): CancelableStream[B, S] = new CancelableStream(switch, value.through(pipe))

  /**
    * Run the current stream with the given retry strategy and making it stoppable with the returned switch
    *
    * @param strategy   the retry strategy to apply
    * @param onCancel   action to apply when the stream gets cancelled
    * @param onFinalize action to apply when the stream completes successfully or with an error
    */
  def run(
      strategy: RetryStrategy[Throwable],
      onCancel: (UUID, S) => UIO[Unit],
      onFinalize: (UUID, S) => UIO[Unit]
  )(implicit scheduler: Scheduler): StreamSwitch[S] = {
    logger.info(s"Stream ${switch.name} is starting")
    val uuid        = switch.uuid
    val task        = value.compile.drain.absorb
    val retriedTask = task.retryingOnSomeErrors(strategy.retryWhen, strategy.policy, strategy.onError)
    handleCallbacks(retriedTask, switch)(onCancel(uuid, _), onFinalize(uuid, _)).runAsyncAndForget
    switch
  }

  private def handleCallbacks(
      f: Task[Unit],
      switch: StreamSwitch[S]
  )(onCancel: S => UIO[Unit], onFinalize: S => UIO[Unit]): Task[Unit] = {
    def terminate: UIO[Unit] = switch.terminated.set(true).hideErrors.void
    def getStopMessage       = switch.stopMessage.get.logAndDiscardErrors(s"fetch stopMessage from stream ${switch.name}")
    f.doOnCancel {
      getStopMessage.flatMap { msg =>
        UIO.delay(logger.debug("Stopped stream {} after cancellation {}", switch.name, msg)) >>
          onCancel(msg) >> terminate
      }
    }.doOnFinish {
      case Some(cause) =>
        getStopMessage.flatMap { msg =>
          UIO.delay(logger.error(s"Stopping stream ${switch.name} after error", cause.toThrowable)) >>
            onFinalize(msg) >> terminate
        }
      case None        =>
        getStopMessage.flatMap { msg =>
          UIO.delay(logger.info("Stopping stream {} after completion {}", switch.name, msg)) >>
            onFinalize(msg) >> terminate
        }
    }
  }
}

object CancelableStream {

  implicit private[CancelableStream] val logger: Logger = Logger[CancelableStream.type]

  def apply[A](name: String, stream: Stream[Task, A])(implicit uuidF: UUIDF): Task[CancelableStream[A, Unit]] =
    apply(name, stream, ())

  def apply[A, S](
      name: String,
      stream: Stream[Task, A],
      initStopMessage: S
  )(implicit uuidF: UUIDF): Task[CancelableStream[A, S]] =
    for {
      uuid        <- uuidF()
      terminated  <- SignallingRef[Task, Boolean](false)
      stopMessage <- SignallingRef[Task, S](initStopMessage)
      interrupter <- SignallingRef[Task, Boolean](false)
      switch       = new StreamSwitch(uuid, name, terminated, stopMessage, interrupter)
    } yield new CancelableStream(switch, stream.interruptWhen(interrupter))
}
