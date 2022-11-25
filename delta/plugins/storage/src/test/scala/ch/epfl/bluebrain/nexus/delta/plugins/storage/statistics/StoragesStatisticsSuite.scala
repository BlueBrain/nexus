package ch.epfl.bluebrain.nexus.delta.plugins.storage.statistics

import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.EventMetricsProjection.{eventMetricsIndex, initMetricsIndex}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.ElasticSearchClient
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.ElasticSearchClient.Refresh
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing.ElasticSearchSink
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.metrics.MetricsStream.{metricsStream, projectRef1, projectRef2}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.{ElasticSearchClientSetup, EventMetricsProjection}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StoragesStatistics
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.SupervisorSetup
import ch.epfl.bluebrain.nexus.testkit.{IOValues, TestHelpers}
import ch.epfl.bluebrain.nexus.testkit.bio.BioSuite
import monix.bio.IO
import munit.AnyFixture

import scala.concurrent.duration.DurationInt

class StoragesStatisticsSuite
    extends BioSuite
    with ElasticSearchClientSetup.Fixture
    with SupervisorSetup.Fixture
    with TestHelpers
    with IOValues {

  override def munitFixtures: Seq[AnyFixture[_]] = List(esClient, supervisor)

  private lazy val client  = esClient()
  private lazy val (sv, _) = supervisor()

  private lazy val sink   = ElasticSearchSink.events(client, 2, 50.millis, index, Refresh.True)
  private val indexPrefix = "delta"
  private val index       = eventMetricsIndex(indexPrefix)

  private val stats = (client: ElasticSearchClient) =>
    StoragesStatistics.apply(client, (_, _) => IO.pure(Iri.unsafe("storageId")), indexPrefix)

  test("Run the event metrics projection") {
    val metricsProjection =
      EventMetricsProjection(sink, sv, _ => metricsStream, initMetricsIndex(client, index))
    metricsProjection.accepted
  }

  test("Correct statistics for storage in project 1") {
    for {
      res <- stats(client).get("storageId", projectRef1)
      _    = assertEquals(res.files, 2L)
      _    = assertEquals(res.spaceUsed, 30L)
    } yield ()
  }
  test("Correct statistics for storage in project 2") {
    for {
      res <- stats(client).get("storageId", projectRef2)
      _    = assertEquals(res.files, 1L)
      _    = assertEquals(res.spaceUsed, 20L)
    } yield ()
  }

}
