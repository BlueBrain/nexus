package ch.epfl.bluebrain.nexus.testkit.clock

import cats.Applicative
import cats.effect.{Clock, IO, Ref, Resource}
import ch.epfl.bluebrain.nexus.testkit.mu.ce.ResourceFixture.IOFixture
import ch.epfl.bluebrain.nexus.testkit.mu.ce.{CatsEffectSuite, ResourceFixture}

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
  private def suiteLocalFixture: IOFixture[MutableClock] = {
    val clock = Ref.of[IO, Instant](Instant.EPOCH).map(new MutableClock(_))
    ResourceFixture.suiteLocal("clock", Resource.eval(clock))
  }

  trait Fixture {
    self: CatsEffectSuite =>
    val mutableClockFixture: ResourceFixture.IOFixture[MutableClock] = suiteLocalFixture
  }
}
