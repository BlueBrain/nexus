package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.deletion

import akka.http.scaladsl.model.Uri.Query
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.{ElasticSearchAction, QueryBuilder}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.metrics.EventMetricsIndex
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.{ElasticSearchClientSetup, Fixtures}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Anonymous, Subject}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.testkit.CirceLiteral
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite
import munit.AnyFixture

class EventMetricsDeletionTaskSuite
    extends NexusSuite
    with ElasticSearchClientSetup.Fixture
    with EventMetricsIndex.Fixture
    with CirceLiteral
    with Fixtures {

  implicit private val subject: Subject = Anonymous

  override def munitFixtures: Seq[AnyFixture[_]] = List(esClient, metricsIndex)

  private lazy val client = esClient()
  private lazy val mIndex = metricsIndex()

  test("Delete all entries for a given project") {
    val index           = mIndex.name
    val projectToDelete = ProjectRef.unsafe("org", "marked-for-deletion")
    val anotherProject  = ProjectRef.unsafe("org", "another")

    val task = new EventMetricsDeletionTask(client, index)

    val operations = List(
      ElasticSearchAction.Index(index, "1", None, json"""{ "project": "$projectToDelete", "number": 1 }"""),
      ElasticSearchAction.Index(index, "2", None, json"""{ "project": "$anotherProject","number" : 2 }"""),
      ElasticSearchAction.Index(index, "3", None, json"""{ "project": "$projectToDelete", "number" : 3 }"""),
      ElasticSearchAction.Index(index, "4", None, json"""{ "project": "$anotherProject", "number" : 4 }""")
    )

    def countMetrics(project: ProjectRef) =
      for {
        query  <- task.searchByProject(project)
        result <- client.search(QueryBuilder(query), Set(index.value), Query.Empty)
      } yield result.total

    for {
      // Indexing and checking count
      _ <- client.createIndex(index, Some(mIndex.mapping), Some(mIndex.settings))
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
