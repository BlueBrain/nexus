package ch.epfl.bluebrain.nexus.testkit.bio

import cats.effect.{Clock, IO}
import monix.bio.UIO

import java.time.Instant
import scala.concurrent.duration.TimeUnit

trait IOFixedClock {

  def bioClock(instant: Instant): Clock[UIO] = new Clock[UIO] {
    override def realTime(unit: TimeUnit): UIO[Long]  = UIO.pure(instant.toEpochMilli)
    override def monotonic(unit: TimeUnit): UIO[Long] = UIO.pure(instant.toEpochMilli)
  }

  implicit def bioClock: Clock[UIO] = bioClock(Instant.EPOCH)

  def ceClock(instant: Instant): Clock[IO] = new Clock[IO] {
    override def realTime(unit: TimeUnit): IO[Long] = IO.pure(instant.toEpochMilli)

    override def monotonic(unit: TimeUnit): IO[Long] = IO.pure(instant.toEpochMilli)
  }

  implicit def ceClock: Clock[IO] = ceClock(Instant.EPOCH)
}

object IOFixedClock extends IOFixedClock
