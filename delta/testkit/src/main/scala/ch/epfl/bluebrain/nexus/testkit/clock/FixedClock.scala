package ch.epfl.bluebrain.nexus.testkit.clock

import cats.Applicative
import cats.effect.{Clock, IO}
import ch.epfl.bluebrain.nexus.testkit.clock.FixedClock.atInstant

import java.time.Instant
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.FiniteDuration

trait FixedClock {
  def clock: Clock[IO] = atInstant(Instant.EPOCH)
}

object FixedClock {
  private val realClock: Clock[IO] = implicitly[Clock[IO]]

  def atInstant(instant: Instant): Clock[IO] = new Clock[IO] {
    override def applicative: Applicative[IO]  = realClock.applicative
    override def monotonic: IO[FiniteDuration] = realClock.monotonic
    override def realTime: IO[FiniteDuration]  = IO.pure(FiniteDuration(instant.toEpochMilli, TimeUnit.MILLISECONDS))
  }
}
