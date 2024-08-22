package ch.epfl.bluebrain.nexus.delta.plugins.storage.statistics

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.ElasticSearchClient
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.ElasticSearchClient.Refresh
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing.ElasticSearchSink
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.metrics.{eventMetricsIndex, EventMetricsProjection}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.{ElasticSearchClientSetup, Fixtures}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.nxvFile
import ch.epfl.bluebrain.nexus.delta.plugins.storage.statistics.StoragesStatisticsSuite.{metricsStream, projectRef1, projectRef2}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StoragesStatistics
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageStatEntry
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.iriStringContextSyntax
import ch.epfl.bluebrain.nexus.delta.sdk.metrics.ProjectScopedMetricStream
import ch.epfl.bluebrain.nexus.delta.sdk.model.metrics.EventMetric.{Created, Deprecated, ProjectScopedMetric, TagDeleted, Tagged, Updated}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Anonymous
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EntityType, Label, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.SupervisorSetup
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.SupervisorSetup.unapply
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite
import ch.epfl.bluebrain.nexus.testkit.mu.ce.PatienceConfig
import io.circe.JsonObject
import io.circe.syntax.KeyOps
import munit.AnyFixture

import java.time.Instant
import scala.concurrent.duration.DurationInt

class StoragesStatisticsSuite
    extends NexusSuite
    with ElasticSearchClientSetup.Fixture
    with SupervisorSetup.Fixture
    with Fixtures {

  override def munitFixtures: Seq[AnyFixture[_]] = List(esClient, supervisor)

  implicit private val patience: PatienceConfig = PatienceConfig(2.seconds, 10.milliseconds)

  private lazy val client     = esClient()
  private lazy val (sv, _, _) = unapply(supervisor())

  private lazy val sink   = ElasticSearchSink.events(client, 2, 50.millis, index, Refresh.False)
  private val indexPrefix = "delta"
  private val index       = eventMetricsIndex(indexPrefix)

  private def stats = (client: ElasticSearchClient) =>
    StoragesStatistics.apply(client, (storage, _) => IO.pure(Iri.unsafe(storage.toString)), indexPrefix)

  test("Run the event metrics projection") {
    val createIndex       = client.createIndex(index, Some(metricsMapping.value), Some(metricsSettings.value)).void
    val metricsProjection = EventMetricsProjection(sink, sv, _ => metricsStream, createIndex)
    metricsProjection.accepted
  }

  test("Correct statistics for storage in project 1") {
    stats(client).get("storageId", projectRef1).assertEquals(StorageStatEntry(2L, 30L)).eventually
  }

  test("Correct statistics for storage in project 2") {
    stats(client).get("storageId", projectRef2).assertEquals(StorageStatEntry(1L, 20L)).eventually
  }

  test("Zero stats for non-existing storage") {
    stats(client).get("none", projectRef1).assertEquals(StorageStatEntry(0L, 0L)).eventually
  }

}

object StoragesStatisticsSuite {
  private val org             = Label.unsafe("org")
  private val proj1           = Label.unsafe("proj1")
  private val proj2           = Label.unsafe("proj2")
  val projectRef1: ProjectRef = ProjectRef(org, proj1)
  val projectRef2: ProjectRef = ProjectRef(org, proj2)

  private val metric1 = ProjectScopedMetric(
    Instant.EPOCH,
    Anonymous,
    1,
    Set(Created),
    projectRef1,
    org,
    iri"http://bbp.epfl.ch/file1",
    Set(nxvFile),
    JsonObject(
      "storage"        := "storageId",
      "newFileWritten" := 1,
      "bytes"          := 10L
    )
  )

  private val metric2 = ProjectScopedMetric(
    Instant.EPOCH,
    Anonymous,
    2,
    Set(Updated),
    projectRef1,
    org,
    iri"http://bbp.epfl.ch/file1",
    Set(nxvFile),
    JsonObject(
      "storage"        := "storageId",
      "newFileWritten" := 1,
      "bytes"          := 20L
    )
  )

  private val metric3 = ProjectScopedMetric(
    Instant.EPOCH,
    Anonymous,
    3,
    Set(Tagged),
    projectRef1,
    org,
    iri"http://bbp.epfl.ch/file1",
    Set(nxvFile),
    JsonObject("storage" := "storageId")
  )

  private val metric4 = ProjectScopedMetric(
    Instant.EPOCH,
    Anonymous,
    4,
    Set(TagDeleted),
    projectRef1,
    org,
    iri"http://bbp.epfl.ch/file1",
    Set(nxvFile),
    JsonObject("storage" := "storageId")
  )

  private val metric5 = ProjectScopedMetric(
    Instant.EPOCH,
    Anonymous,
    1,
    Set(Created),
    projectRef2,
    org,
    iri"http://bbp.epfl.ch/file2",
    Set(nxvFile),
    JsonObject(
      "storage"        := "storageId",
      "newFileWritten" := 1,
      "bytes"          := 20L
    )
  )

  private val metric6 = ProjectScopedMetric(
    Instant.EPOCH,
    Anonymous,
    2,
    Set(Deprecated),
    projectRef2,
    org,
    iri"http://bbp.epfl.ch/file2",
    Set(nxvFile),
    JsonObject("storage" := "storageId")
  )

  private val metricsStream =
    ProjectScopedMetricStream(EntityType("entity"), metric1, metric2, metric3, metric4, metric5, metric6)

}
