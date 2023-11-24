package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.metrics

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.metrics.MetricsStream._
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.{EventMetricsProjection, Fixtures}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.{CacheSink, ProjectionProgress, SupervisorSetup}
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite
import ch.epfl.bluebrain.nexus.testkit.mu.ce.PatienceConfig
import io.circe.Json
import io.circe.syntax.EncoderOps
import munit.AnyFixture

import java.time.Instant
import scala.concurrent.duration.DurationInt

class EventMetricsProjectionSuite extends NexusSuite with SupervisorSetup.Fixture with Fixtures {

  override def munitFixtures: Seq[AnyFixture[_]] = List(supervisor)

  implicit private val patienceConfig: PatienceConfig = PatienceConfig(2.seconds, 10.millis)

  private lazy val sv = supervisor().supervisor
  private val sink    = CacheSink.events[Json]

  test("Start the metrics projection") {
    for {
      _ <- EventMetricsProjection(
             sink,
             sv,
             _ => metricsStream.take(2),
             IO.unit
           )
      _ <- sv.describe(EventMetricsProjection.projectionMetadata.name)
             .map(_.map(_.progress))
             .assertEquals(Some(ProjectionProgress(Offset.at(2L), Instant.EPOCH, 2, 0, 0)))
             .eventually
    } yield ()
  }

  test("Sink has the correct metrics") {
    assertEquals(sink.successes.size, 2)
    assert(sink.dropped.isEmpty)
    assert(sink.failed.isEmpty)
    assert(sink.successes.values.toSet.contains(metric1.asJson))
    assert(sink.successes.values.toSet.contains(metric2.asJson))
  }

}
