package ch.epfl.bluebrain.nexus.delta.kernel.utils

import cats.effect.{Clock, IO}
import cats.implicits.toFlatMapOps
import ch.epfl.bluebrain.nexus.delta.kernel.utils.IOUtils.IoOps
import munit.FunSuite

import java.time.Instant
import scala.concurrent.duration._

class IOUtilsSuite extends FunSuite {
  test("Test timer") {
    implicit val clock = new TimeMachine

    val (duration, value) = IO
      .pure("value")
      .flatTap(_ => IO.delay(clock.goForwards(5.seconds)))
      .timed
      .unsafeRunSync()

    assertEquals(duration, 8.seconds)
    assertEquals(value, "value")
  }

  class TimeMachine extends Clock[IO] {
    private var currentTime                          = Instant.now
    def goForwards(duration: FiniteDuration): Unit = {
      currentTime = currentTime.plusMillis(duration.toMillis)
    }
    override def realTime(unit: TimeUnit): IO[Long]  = IO.pure(currentTime.toEpochMilli)
    override def monotonic(unit: TimeUnit): IO[Long] = IO.pure(currentTime.toEpochMilli)
  }
}
