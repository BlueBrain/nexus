package ch.epfl.bluebrain.nexus.testkit.bio

import cats.effect.Clock
import monix.bio.{IO, UIO}

import java.time.Instant
import scala.concurrent.duration.TimeUnit

trait IOFixedClock {

  def ioClock(instant: Instant): Clock[UIO] = new Clock[UIO] {
    override def realTime(unit: TimeUnit): UIO[Long]  = IO.pure(instant.toEpochMilli)
    override def monotonic(unit: TimeUnit): UIO[Long] = IO.pure(instant.toEpochMilli)
  }

  implicit def ioClock: Clock[UIO] = ioClock(Instant.EPOCH)
}

object IOFixedClock extends IOFixedClock
