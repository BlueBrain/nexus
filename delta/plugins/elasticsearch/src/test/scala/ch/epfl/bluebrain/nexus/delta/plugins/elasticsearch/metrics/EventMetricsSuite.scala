package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.metrics

import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.ElasticSearchClient.BulkResponse
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.{ElasticSearchClientSetup, Fixtures}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.model.metrics.EventMetric
import ch.epfl.bluebrain.nexus.delta.sdk.model.metrics.EventMetric.ProjectScopedMetric
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Anonymous
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite
import io.circe.JsonObject
import io.circe.syntax.EncoderOps
import munit.AnyFixture

import java.time.Instant

class EventMetricsSuite
    extends NexusSuite
    with ElasticSearchClientSetup.Fixture
    with EventMetricsIndex.Fixture
    with Fixtures {

  override def munitFixtures: Seq[AnyFixture[?]] = List(esClient, metricsIndex)

  private lazy val client = esClient()
  private lazy val mIndex = metricsIndex()

  private lazy val index             = mIndex.name
  private lazy val eventMetricsQuery = EventMetrics(client, mIndex)

  private val project1 = ProjectRef.unsafe("org", "project1")
  private val project2 = ProjectRef.unsafe("org", "project2")
  private val project3 = ProjectRef.unsafe("org", "project3")

  private val id1 = nxv + "id1"
  private val id2 = nxv + "id2"
  private val id3 = nxv + "id3"

  private val epoch = Instant.EPOCH

  private def createMetric(project: ProjectRef, id: Iri, rev: Int) =
    ProjectScopedMetric(
      epoch,
      Anonymous,
      rev,
      Set(EventMetric.Created),
      project,
      id,
      Set(nxv + "Type11"),
      JsonObject.empty
    )

  private val event11 = createMetric(project1, id1, 1)
  private val event12 = createMetric(project1, id1, 2)
  private val event13 = createMetric(project1, id2, 1)

  private val event21 = createMetric(project2, id2, 1)
  private val event22 = createMetric(project2, id3, 1)

  private val event31 = createMetric(project3, id1, 1)

  private val allEvents = Vector(event11, event12, event13, event21, event22, event31)

  test("Init and populate the index") {
    for {
      _ <- eventMetricsQuery.init
      _ <- client.existsIndex(index).assertEquals(true)
      _ <- eventMetricsQuery.index(allEvents).assertEquals(BulkResponse.Success)
      _ <- client.refresh(index)
      _ <- client.count(index.value).assertEquals(allEvents.size.toLong)
    } yield ()
  }

  test("Query for a resource history") {
    val expected = List(event11, event12).map(_.asJsonObject)
    eventMetricsQuery
      .history(project1, id1)
      .map(_.sources)
      .assertEquals(expected)
  }

  test("Delete by project") {
    for {
      _ <- eventMetricsQuery.deleteByProject(project2)
      _ <- client.refresh(index)
      // Events in project2 should be deleted
      _ <- eventMetricsQuery.history(project2, id2).map(_.total).assertEquals(0L)
      _ <- eventMetricsQuery.history(project2, id3).map(_.total).assertEquals(0L)
      // Others should be preserved
      _ <- eventMetricsQuery.history(project1, id1).map(_.total).assertEquals(2L)
      _ <- eventMetricsQuery.history(project3, id1).map(_.total).assertEquals(1L)
    } yield ()
  }

  test("Delete by resource") {
    for {
      _ <- eventMetricsQuery.deleteByResource(project1, id1)
      _ <- client.refresh(index)
      // Events for  should be deleted
      _ <- eventMetricsQuery.history(project1, id1).map(_.total).assertEquals(0L)
      _ <- eventMetricsQuery.history(project1, id2).map(_.total).assertEquals(1L)
      _ <- eventMetricsQuery.history(project3, id1).map(_.total).assertEquals(1L)
    } yield ()
  }

}
