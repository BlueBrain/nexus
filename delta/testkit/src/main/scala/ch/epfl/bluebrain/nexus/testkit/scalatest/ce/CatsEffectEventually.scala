package ch.epfl.bluebrain.nexus.testkit.scalatest.ce

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategyConfig.MaximumCumulativeDelayConfig
import ch.epfl.bluebrain.nexus.delta.kernel.syntax._
import ch.epfl.bluebrain.nexus.delta.kernel.{Logger, RetryStrategy}
import ch.epfl.bluebrain.nexus.testkit.scalatest.ce.CatsEffectEventually.logger
import org.scalactic.source.Position
import org.scalatest.Assertions
import org.scalatest.enablers.Retrying
import org.scalatest.exceptions.TestFailedException
import org.scalatest.time.Span

trait CatsEffectEventually { self: Assertions =>
  implicit def ioRetrying[T]: Retrying[IO[T]] = new Retrying[IO[T]] {
    override def retry(timeout: Span, interval: Span, pos: Position)(fun: => IO[T]): IO[T] = {
      val strategy = RetryStrategy[Throwable](
        MaximumCumulativeDelayConfig(timeout, interval),
        {
          case _: TestFailedException => true
          case _                      => false
        },
        onError = (err, details) =>
          IO.whenA(details.givingUp) {
            logger.error(err)(
              s"Giving up on ${err.getClass.getSimpleName}, ${details.retriesSoFar} retries after ${details.cumulativeDelay}."
            )
          }
      )
      fun
        .retry(strategy)
        .adaptError { case e: AssertionError =>
          fail(s"assertion failed after retrying with eventually: ${e.getMessage}", e)
        }
    }
  }
}

object CatsEffectEventually {
  private val logger = Logger[CatsEffectEventually]
}
