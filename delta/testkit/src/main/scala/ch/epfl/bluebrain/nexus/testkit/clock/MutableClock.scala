package ch.epfl.bluebrain.nexus.testkit.clock

import cats.Applicative
import cats.effect.{Clock, IO, Ref, Resource}
import munit.CatsEffectSuite
import munit.catseffect.IOFixture

import java.time.Instant
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.FiniteDuration

final class MutableClock(value: Ref[IO, Instant]) extends Clock[IO] {
  private val realClock: Clock[IO]           = implicitly[Clock[IO]]
  override def applicative: Applicative[IO]  = realClock.applicative
  override def monotonic: IO[FiniteDuration] =
    value.get.map(_.toEpochMilli).map(FiniteDuration(_, TimeUnit.MILLISECONDS))
  override def realTime: IO[FiniteDuration]  =
    value.get.map(_.toEpochMilli).map(FiniteDuration(_, TimeUnit.MILLISECONDS))
  def set(instant: Instant): IO[Unit]        = value.set(instant)
}

object MutableClock {
  trait Fixture {
    self: CatsEffectSuite =>
    val mutableClockFixture: IOFixture[MutableClock] = {
      ResourceSuiteLocalFixture(
        "clock",
        Resource.eval(
          Ref
            .of[IO, Instant](Instant.EPOCH)
            .map(new MutableClock(_))
        )
      )
    }
  }
}
