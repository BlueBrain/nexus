package ch.epfl.bluebrain.nexus.testkit.clock

import cats.effect.{Clock, IO}
import ch.epfl.bluebrain.nexus.testkit.clock.FixedClock.atInstant

import java.time.Instant
import scala.concurrent.duration.TimeUnit

trait FixedClock {
  implicit def clock: Clock[IO] = atInstant(Instant.EPOCH)
}

object FixedClock {
  private val realClock: Clock[IO] = Clock.create

  def atInstant(instant: Instant): Clock[IO] = new Clock[IO] {
    override def realTime(unit: TimeUnit): IO[Long] = IO.pure(instant.toEpochMilli)

    override def monotonic(unit: TimeUnit): IO[Long] = realClock.monotonic(unit)
  }
}
