package ch.epfl.bluebrain.nexus.ship

import cats.Applicative
import cats.effect.{Clock, IO, Ref}

import java.time.Instant
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.FiniteDuration

/**
  * Mutable clock used for the migration
  */
class EventClock(instant: Ref[IO, Instant]) extends Clock[IO] {

  def setInstant(value: Instant): IO[Unit] = instant.set(value)

  override def applicative: Applicative[IO] = Applicative[IO]

  override def monotonic: IO[FiniteDuration] = toDuration

  override def realTime: IO[FiniteDuration] = toDuration

  private def toDuration: IO[FiniteDuration] = instant.get.map { i =>
    FiniteDuration(i.toEpochMilli, TimeUnit.MILLISECONDS)
  }
}

object EventClock {
  def init(): IO[EventClock] = Ref.of[IO, Instant](Instant.EPOCH).map { ref => new EventClock(ref) }
}
