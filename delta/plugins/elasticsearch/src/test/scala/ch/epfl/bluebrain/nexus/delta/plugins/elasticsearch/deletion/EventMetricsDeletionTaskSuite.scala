package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.deletion

import akka.http.scaladsl.model.Uri.Query
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.{ElasticSearchBulk, QueryBuilder}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.{ElasticSearchClientSetup, EventMetricsProjection, Fixtures}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Anonymous, Subject}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.testkit.bio.BioRunContext
import ch.epfl.bluebrain.nexus.testkit.mu.bio.BioAssertions
import ch.epfl.bluebrain.nexus.testkit.mu.ce.CatsEffectSuite
import ch.epfl.bluebrain.nexus.testkit.{CirceLiteral, TestHelpers}
import munit.AnyFixture

class EventMetricsDeletionTaskSuite
    extends CatsEffectSuite
    with BioAssertions
    with BioRunContext
    with ElasticSearchClientSetup.Fixture
    with CirceLiteral
    with TestHelpers
    with Fixtures {

  implicit private val subject: Subject = Anonymous

  override def munitFixtures: Seq[AnyFixture[_]] = List(esClient)

  private lazy val client = esClient()

  test("Delete all entries for a given project") {
    val prefix          = "test"
    val index           = EventMetricsProjection.eventMetricsIndex(prefix)
    val projectToDelete = ProjectRef.unsafe("org", "marked-for-deletion")
    val anotherProject  = ProjectRef.unsafe("org", "another")

    val task = new EventMetricsDeletionTask(client, prefix)

    val operations = List(
      ElasticSearchBulk.Index(index, "1", json"""{ "project": "$projectToDelete", "number": 1 }"""),
      ElasticSearchBulk.Index(index, "2", json"""{ "project": "$anotherProject","number" : 2 }"""),
      ElasticSearchBulk.Index(index, "3", json"""{ "project": "$projectToDelete", "number" : 3 }"""),
      ElasticSearchBulk.Index(index, "4", json"""{ "project": "$anotherProject", "number" : 4 }""")
    )

    def countMetrics(project: ProjectRef) =
      for {
        query  <- task.searchByProject(project)
        result <- client.search(QueryBuilder(query), Set(index.value), Query.Empty)
      } yield result.total

    for {
      // Indexing and checking count
      _ <- client.createIndex(index, Some(metricsMapping.value), Some(metricsSettings.value))
      _ <- client.bulk(operations)
      _ <- client.refresh(index)
      _ <- client.count(index.value).assertEquals(4L)
      // Running the task and checking the index again
      _ <- task(projectToDelete)
      _ <- client.refresh(index)
      _ <- client.count(index.value).assertEquals(2L)
      _ <- countMetrics(projectToDelete).assertEquals(0L)
      _ <- countMetrics(anotherProject).assertEquals(2L)
    } yield ()
  }

}
