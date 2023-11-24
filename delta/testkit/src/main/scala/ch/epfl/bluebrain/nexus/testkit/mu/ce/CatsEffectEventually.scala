package ch.epfl.bluebrain.nexus.testkit.mu.ce

import cats.effect.IO
import cats.implicits.catsSyntaxMonadError
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategy
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategyConfig.MaximumCumulativeDelayConfig
import ch.epfl.bluebrain.nexus.delta.kernel.syntax._
import munit.{Assertions, CatsEffectAssertions, Location}

trait CatsEffectEventually { self: Assertions with CatsEffectAssertions =>
  implicit class CatsEffectEventuallyOps[A](io: IO[A]) {
    def eventually(implicit loc: Location, patience: PatienceConfig): IO[A] = {
      val strategy = RetryStrategy[Throwable](
        MaximumCumulativeDelayConfig(patience.timeout, patience.interval),
        {
          case _: AssertionError => true
          case _                 => false
        },
        onError = (_, _) => IO.unit
      )
      io
        .retry(strategy)
        .adaptError { case e: AssertionError =>
          fail(s"assertion failed after retrying with eventually: ${e.getMessage}", e)
        }
    }
  }
}
