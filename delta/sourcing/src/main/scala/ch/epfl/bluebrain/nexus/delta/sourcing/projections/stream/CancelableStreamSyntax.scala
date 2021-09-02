package ch.epfl.bluebrain.nexus.delta.sourcing.projections.stream

import cats.effect.Timer
import ch.epfl.bluebrain.nexus.delta.kernel.utils.IOUtils
import ch.epfl.bluebrain.nexus.delta.sourcing.syntax._
import fs2.Stream
import fs2.concurrent.SignallingRef
import monix.bio.{Task, UIO}

import java.time.Instant
import scala.concurrent.duration._

trait CancelableStreamSyntax {
  implicit final def cancelableStreamSyntax[A, S](stream: CancelableStream[A, S]): CancelableStreamOps[A, S] =
    new CancelableStreamOps(stream)
}

class CancelableStreamOps[A, S](private val stream: CancelableStream[A, S]) extends AnyVal {

  /**
    * Stop the stream after an inactivity timeout
    *
    * @param duration
    *   the idle duration after which the stream will be stopped
    */
  def idleTimeout(
      duration: FiniteDuration,
      stopOnIdleTimeout: S
  )(implicit timer: Timer[UIO]): CancelableStream[Unit, S] =
    stream.through { s =>
      Stream.eval(IOUtils.instant.flatMap(SignallingRef[Task, Instant](_))).flatMap { lastEventTime =>
        val tick = Stream
          .repeatEval[Task, Unit] {
            for {
              nowInstant         <- IOUtils.instant
              latestEventInstant <- lastEventTime.get
              prevDelay           = nowInstant diff latestEventInstant
              nextDelay           = 0.millis.max(duration.minus(prevDelay))
              _                  <- if (prevDelay gteq duration) stream.stop(stopOnIdleTimeout) else timer.sleep(nextDelay)
            } yield ()
          }
        s.evalMap(_ => IOUtils.instant.flatMap(lastEventTime.set).void) mergeHaltBoth tick
      }
    }
}
