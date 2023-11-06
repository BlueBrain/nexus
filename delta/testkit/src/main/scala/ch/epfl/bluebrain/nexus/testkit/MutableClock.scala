package ch.epfl.bluebrain.nexus.testkit

import cats.effect.concurrent.Ref
import cats.effect.{Clock, IO, Resource}
import ch.epfl.bluebrain.nexus.testkit.mu.ce.ResourceFixture.IOFixture
import ch.epfl.bluebrain.nexus.testkit.mu.ce.{CatsEffectSuite, ResourceFixture}

import java.time.Instant
import scala.concurrent.duration.TimeUnit

final class MutableClock(value: Ref[IO, Instant]) extends Clock[IO] {

  def set(instant: Instant): IO[Unit]             = value.set(instant)
  override def realTime(unit: TimeUnit): IO[Long] = value.get.map(_.toEpochMilli)

  override def monotonic(unit: TimeUnit): IO[Long] = value.get.map(_.toEpochMilli)
}
object MutableClock {
  private def suiteLocalFixture: IOFixture[MutableClock] = {
    val clock = Ref.of[IO, Instant](Instant.EPOCH).map(new MutableClock(_))
    ResourceFixture.suiteLocal("clock", Resource.eval(clock))
  }

  trait Fixture {
    self: CatsEffectSuite =>
    val clock: ResourceFixture.IOFixture[MutableClock] = suiteLocalFixture
  }
}
