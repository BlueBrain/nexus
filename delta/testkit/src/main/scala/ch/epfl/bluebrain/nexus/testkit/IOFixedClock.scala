package ch.epfl.bluebrain.nexus.testkit

import java.time.Instant

import cats.effect.Clock
import monix.bio.{IO, UIO}

import scala.concurrent.duration.TimeUnit

trait IOFixedClock {

  implicit def ioClock: Clock[UIO] =
    new Clock[UIO] {
      override def realTime(unit: TimeUnit): UIO[Long]  = IO.pure(Instant.EPOCH.toEpochMilli)
      override def monotonic(unit: TimeUnit): UIO[Long] = IO.pure(Instant.EPOCH.toEpochMilli)
    }
}

object IOFixedClock extends IOFixedClock
