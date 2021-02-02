package ch.epfl.bluebrain.nexus.migration

import cats.effect.Clock
import monix.bio.{IO, UIO}

import java.time.Instant
import scala.concurrent.duration.TimeUnit

/**
  * Mutable clock used for the migration
  */
class MutableClock(var instant: Instant) extends Clock[UIO] {

  def setInstant(value: Instant): Unit = instant = value

  override def realTime(unit: TimeUnit): UIO[Long] = IO.pure(instant.toEpochMilli)

  override def monotonic(unit: TimeUnit): UIO[Long] = IO.pure(instant.toEpochMilli)
}
