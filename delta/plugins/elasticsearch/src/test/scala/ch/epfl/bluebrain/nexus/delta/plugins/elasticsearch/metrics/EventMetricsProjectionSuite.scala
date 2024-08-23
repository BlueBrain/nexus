package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.metrics

import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.ElasticSearchClient.Refresh
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing.ElasticSearchSink
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.metrics.EventMetricsProjectionSuite.{metric1, metric2}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.{ElasticSearchClientSetup, Fixtures}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.iriStringContextSyntax
import ch.epfl.bluebrain.nexus.delta.sdk.metrics.ProjectScopedMetricStream
import ch.epfl.bluebrain.nexus.delta.sdk.model.metrics.EventMetric.{Created, ProjectScopedMetric, Updated}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Anonymous
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EntityType, Label, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.{ProjectionProgress, SupervisorSetup}
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite
import ch.epfl.bluebrain.nexus.testkit.mu.ce.PatienceConfig
import io.circe.syntax.{EncoderOps, KeyOps}
import io.circe.{Json, JsonObject}
import munit.AnyFixture

import java.time.Instant
import scala.concurrent.duration.DurationInt

class EventMetricsProjectionSuite
    extends NexusSuite
    with SupervisorSetup.Fixture
    with ElasticSearchClientSetup.Fixture
    with Fixtures {

  override def munitFixtures: Seq[AnyFixture[_]] = List(supervisor, esClient)

  implicit private val patienceConfig: PatienceConfig = PatienceConfig(2.seconds, 10.millis)

  private val index = eventMetricsIndex("nexus")

  private lazy val sv     = supervisor().supervisor
  private lazy val client = esClient()
  private lazy val sink   = ElasticSearchSink.events(client, 2, 50.millis, index, Refresh.True)

  test("Start the metrics projection and index metrics") {
    def createIndex = client
      .createIndex(index, Some(metricsMapping.value), Some(metricsSettings.value))
      .assertEquals(true)
    for {
      _ <- EventMetricsProjection(sink, sv, _ => EventMetricsProjectionSuite.stream, createIndex)
      _ <- sv.describe(EventMetricsProjection.projectionMetadata.name)
             .map(_.map(_.progress))
             .assertEquals(Some(ProjectionProgress(Offset.at(2L), Instant.EPOCH, 2, 0, 0)))
             .eventually
      _ <- client.count(index.value).assertEquals(2L)
      // Asserting the sources
      _ <- client.getSource[Json](index, metric1.eventId).assertEquals(metric1.asJson)
      _ <- client.getSource[Json](index, metric2.eventId).assertEquals(metric2.asJson)
    } yield ()
  }
}

object EventMetricsProjectionSuite {
  private val org                     = Label.unsafe("org")
  private val proj1                   = Label.unsafe("proj1")
  private val projectRef1: ProjectRef = ProjectRef(org, proj1)

  private val metric1: ProjectScopedMetric = ProjectScopedMetric(
    Instant.EPOCH,
    Anonymous,
    1,
    Set(Created),
    projectRef1,
    org,
    iri"http://bbp.epfl.ch/file1",
    Set(nxv + "Resource1", nxv + "Resource2"),
    JsonObject("extraField" := "extraValue")
  )
  private val metric2: ProjectScopedMetric = ProjectScopedMetric(
    Instant.EPOCH,
    Anonymous,
    2,
    Set(Updated),
    projectRef1,
    org,
    iri"http://bbp.epfl.ch/file1",
    Set(nxv + "Resource1", nxv + "Resource3"),
    JsonObject(
      "extraField"  := "extraValue",
      "extraField2" := 42
    )
  )

  private val stream = ProjectScopedMetricStream(EntityType("entity"), metric1, metric2)
}
