package ch.epfl.bluebrain.nexus.testkit.ce

import java.time.Instant
import cats.effect.{Clock, IO}

import scala.concurrent.duration.TimeUnit

trait IOFixedClock {

  def ioClock(instant: Instant): Clock[IO] = new Clock[IO] {
    override def realTime(unit: TimeUnit): IO[Long]  = IO.pure(instant.toEpochMilli)
    override def monotonic(unit: TimeUnit): IO[Long] = IO.pure(instant.toEpochMilli)
  }

  implicit def ioClock: Clock[IO] = ioClock(Instant.EPOCH)
}

object IOFixedClock extends IOFixedClock
