package ch.epfl.bluebrain.nexus.testkit

import cats.effect.concurrent.Ref
import cats.effect.{Clock, Resource}
import ch.epfl.bluebrain.nexus.testkit.bio.ResourceFixture.TaskFixture
import ch.epfl.bluebrain.nexus.testkit.bio.{BioSuite, ResourceFixture}
import monix.bio.{Task, UIO}

import java.time.Instant
import scala.concurrent.duration.TimeUnit

final class MutableClock(value: Ref[Task, Instant]) extends Clock[UIO] {

  def set(instant: Instant): UIO[Unit]             = value.set(instant).hideErrors
  override def realTime(unit: TimeUnit): UIO[Long] = value.get.map(_.toEpochMilli).hideErrors

  override def monotonic(unit: TimeUnit): UIO[Long] = value.get.map(_.toEpochMilli).hideErrors
}
object MutableClock {
  private def suiteLocalFixture: TaskFixture[MutableClock] = {
    val clock = Ref.of[Task, Instant](Instant.EPOCH).map(new MutableClock(_))
    ResourceFixture.suiteLocal("clock", Resource.eval(clock))
  }

  trait Fixture {
    self: BioSuite =>
    val clock: ResourceFixture.TaskFixture[MutableClock] = suiteLocalFixture
  }
}
