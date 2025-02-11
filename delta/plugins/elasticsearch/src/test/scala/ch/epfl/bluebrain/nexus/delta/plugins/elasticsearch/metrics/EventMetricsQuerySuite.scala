package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.metrics

import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.ElasticSearchAction
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.ElasticSearchClient.BulkResponse
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.{ElasticSearchClientSetup, Fixtures}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite
import io.circe.JsonObject
import io.circe.syntax.{EncoderOps, KeyOps}
import munit.AnyFixture

class EventMetricsQuerySuite extends NexusSuite with ElasticSearchClientSetup.Fixture with Fixtures {

  override def munitFixtures: Seq[AnyFixture[_]] = List(esClient)

  private lazy val client = esClient()

  private val prefix = "nexus"

  private val index = eventMetricsIndex(prefix)

  private lazy val eventMetricsQuery = EventMetricsQuery(client, prefix)

  private val project1 = ProjectRef.unsafe("org", "project1")
  private val project2 = ProjectRef.unsafe("org", "project2")

  private val id1 = nxv + "id1"
  private val id2 = nxv + "id1"

  private val event11 = JsonObject(
    "project" := project1,
    "@type"   := Set(nxv + "Type11"),
    "@id"     := id1,
    "rev"     := 1
  )

  private val event12 = JsonObject(
    "project" := project1,
    "@type"   := Set(nxv + "Type11", nxv + "Type12"),
    "@id"     := id1,
    "rev"     := 2
  )

  private val event21 = JsonObject(
    "project" := project2,
    "@type"   := nxv + "Type2",
    "@id"     := id2,
    "rev"     := 1
  )

  test("Query for a resource history") {
    for {
      _      <- client.createIndex(index, Some(metricsMapping.value), Some(metricsSettings.value)).assertEquals(true)
      bulk    = List(event11, event12, event21).zipWithIndex.map { case (event, i) =>
                  ElasticSearchAction.Index(index, i.toString, None, event.asJson)
                }
      _      <- client.bulk(bulk).assertEquals(BulkResponse.Success)
      _      <- client.refresh(index)
      _      <- client.count(index.value).assertEquals(3L)
      result <- eventMetricsQuery.history(project1, id1)
    } yield {
      assertEquals(result.total, 2L)
      assertEquals(result.sources, List(event11, event12))
    }
  }

}
