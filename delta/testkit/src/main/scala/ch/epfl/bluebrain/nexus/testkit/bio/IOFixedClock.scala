package ch.epfl.bluebrain.nexus.testkit.bio

import cats.effect.{Clock, IO}

import java.time.Instant
import scala.concurrent.duration.TimeUnit

trait IOFixedClock {

  private val realClock: Clock[IO] = Clock.create

  def ceClock(instant: Instant): Clock[IO] = new Clock[IO] {
    override def realTime(unit: TimeUnit): IO[Long]  = IO.pure(instant.toEpochMilli)
    override def monotonic(unit: TimeUnit): IO[Long] = realClock.monotonic(unit)
  }

  implicit def ceClock: Clock[IO] = ceClock(Instant.EPOCH)
}

object IOFixedClock extends IOFixedClock
