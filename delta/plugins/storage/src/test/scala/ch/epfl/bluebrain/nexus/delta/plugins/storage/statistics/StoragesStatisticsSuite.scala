package ch.epfl.bluebrain.nexus.delta.plugins.storage.statistics

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.EventMetricsProjection.eventMetricsIndex
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.ElasticSearchClient
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.ElasticSearchClient.Refresh
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing.ElasticSearchSink
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.metrics.MetricsStream.{metricsStream, projectRef1, projectRef2}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchFiles
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.{ElasticSearchClientSetup, EventMetricsProjection}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StoragesStatistics
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageStatEntry
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.SupervisorSetup
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.SupervisorSetup.unapply
import ch.epfl.bluebrain.nexus.testkit.TestHelpers
import ch.epfl.bluebrain.nexus.testkit.bio.BioRunContext
import ch.epfl.bluebrain.nexus.testkit.mu.bio.{BIOValues, PatienceConfig}
import ch.epfl.bluebrain.nexus.testkit.mu.ce.CatsEffectSuite
import munit.AnyFixture

import scala.concurrent.duration.DurationInt

class StoragesStatisticsSuite
    extends CatsEffectSuite
    with BioRunContext
    with ElasticSearchClientSetup.Fixture
    with BIOValues
    with SupervisorSetup.Fixture
    with TestHelpers {

  override def munitFixtures: Seq[AnyFixture[_]] = List(esClient, supervisor)

  implicit private val patience: PatienceConfig = PatienceConfig(2.seconds, 10.milliseconds)

  private lazy val client     = esClient()
  private lazy val (sv, _, _) = unapply(supervisor())

  private lazy val sink     = ElasticSearchSink.events(client, 2, 50.millis, index, Refresh.False)
  private val indexPrefix   = "delta"
  private val index         = eventMetricsIndex(indexPrefix)
  private lazy val files         = ElasticSearchFiles().unsafeRunSync()
  private lazy val mapping  = files.metricsMapping
  private lazy val settings = files.metricsSettings

  private def stats = (client: ElasticSearchClient) =>
    StoragesStatistics.apply(client, (storage, _) => IO.pure(Iri.unsafe(storage.toString)), indexPrefix)

  test("Run the event metrics projection") {
    val createIndex       = client.createIndex(index, Some(mapping.value), Some(settings.value)).void
    val metricsProjection = EventMetricsProjection(sink, sv, _ => metricsStream, createIndex)
    metricsProjection.accepted
  }

  test("Correct statistics for storage in project 1") {
    stats(client).get("storageId", projectRef1).eventually(StorageStatEntry(2L, 30L))
  }

  test("Correct statistics for storage in project 2") {
    stats(client).get("storageId", projectRef2).eventually(StorageStatEntry(1L, 20L))
  }

  test("Zero stats for non-existing storage") {
    stats(client).get("none", projectRef1).eventually(StorageStatEntry(0L, 0L))
  }

}
