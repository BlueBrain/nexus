package ch.epfl.bluebrain.nexus.delta.sourcing.projections.stream

import cats.effect.ExitCase
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategy
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import com.typesafe.scalalogging.Logger
import fs2.Stream
import fs2.concurrent.SignallingRef
import monix.bio.{Task, UIO}
import monix.execution.Scheduler
import retry.syntax.all._
import scala.concurrent.duration._

import java.util.UUID

/**
  * Switch identified by ann uuid allowing to stop a stream
  */
final class StreamSwitch private (
    val uuid: UUID,
    interrupter: SignallingRef[Task, Boolean],
    terminated: SignallingRef[Task, Boolean]
) {

  /**
    * Sets the interrupter to true to stop the running stream
    */
  def stop: Task[Unit] = interrupter.set(true).void >> terminated.get.restartUntil(_ == true).timeout(3.seconds).void
}

object StreamSwitch {

  private val logger: Logger = Logger[StreamSwitch.type]

  /**
    * Run the provided stream woth the given retry strategy and making it interruptible with the returned switch
    * @param streamName the name of the stream
    * @param streamTask the stream to run
    * @param retryStrategy the retry strategy to apply
    * @param onCancel action to apply when the stream gets cancelled
    * @param onFinalize action to apply when the stream completes successfully or with an error
    */
  def run[A](
      streamName: String,
      streamTask: Task[Stream[Task, A]],
      retryStrategy: RetryStrategy[Throwable],
      onCancel: UUID => UIO[Unit],
      onFinalize: UUID => UIO[Unit]
  )(implicit uuidF: UUIDF, scheduler: Scheduler): Task[StreamSwitch] =
    for {
      _          <- UIO.delay(logger.debug("Starting stream {}...", streamName))
      uuid       <- uuidF()
      terminated <- SignallingRef[Task, Boolean](false)
      switch     <- SignallingRef[Task, Boolean](false).map { interrupter =>
                      def terminate: UIO[Unit] = terminated.set(true).hideErrors.void

                      val program = streamTask
                        .flatMap { stream =>
                          stream
                            .interruptWhen(interrupter)
                            .onFinalizeCase {
                              case ExitCase.Completed =>
                                UIO.delay(logger.debug(s"Stream $streamName has been successfully completed."))
                              case ExitCase.Error(e)  =>
                                UIO.delay(logger.error(s"Stream $streamName events has failed.", e))
                              case ExitCase.Canceled  =>
                                UIO.delay(logger.warn(s"Stream $streamName got cancelled."))
                            }
                            .compile
                            .drain
                        }
                        .retryingOnSomeErrors(retryStrategy.retryWhen, retryStrategy.policy, retryStrategy.onError)

                      // When the stream ends, after applying the retry strategy, we apply the callbacks
                      program
                        .doOnCancel {
                          UIO.delay(logger.info("Stopping stream {} after cancellation...", streamName)) >>
                            onCancel(uuid) >> terminate
                        }
                        .doOnFinish {
                          case Some(cause) =>
                            UIO.delay(logger.error(s"Stopping stream $streamName after error", cause.toThrowable)) >>
                              onFinalize(uuid) >> terminate
                          case None        =>
                            UIO.delay(logger.info("Stopping stream {} after completion", streamName)) >>
                              onFinalize(uuid) >> terminate
                        }
                        .runAsyncAndForget

                      new StreamSwitch(uuid, interrupter, terminated)
                    }
    } yield switch

}
