package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.metrics

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.ElasticSearchAction.Index
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.{BulkResponse, ElasticSearchClient, QueryBuilder, Refresh}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.model.metrics.EventMetric.ProjectScopedMetric
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import io.circe.JsonObject
import io.circe.literal.JsonStringContext
import io.circe.syntax.EncoderOps
import org.http4s.Query

trait FetchHistory {
  def history(project: ProjectRef, id: Iri): IO[SearchResults[JsonObject]]
}

trait EventMetrics extends FetchHistory {

  def init: IO[Unit]

  def destroy: IO[Unit]

  def index(bulk: Vector[ProjectScopedMetric]): IO[BulkResponse]

  def deleteByProject(project: ProjectRef): IO[Unit]

  def deleteByResource(project: ProjectRef, id: Iri): IO[Unit]
}

object EventMetrics {

  def apply(client: ElasticSearchClient, metricIndex: MetricsIndexDef): EventMetrics = new EventMetrics {

    private val index = metricIndex.name

    override def init: IO[Unit] =
      client.createIndex(index, Some(metricIndex.mapping), Some(metricIndex.settings)).void

    override def destroy: IO[Unit] = client.deleteIndex(index).void

    override def index(bulk: Vector[ProjectScopedMetric]): IO[BulkResponse] = {
      val actions = bulk.map { metric =>
        Index(index, metric.eventId, None, metric.asJson)
      }
      client.bulk(actions, Refresh.False)
    }

    private def deleteByProjectQuery(project: ProjectRef) = IO.fromOption(
      json"""{"query": {"term": {"project": ${project.asJson} } } }""".asObject
    )(new IllegalStateException("Failed to convert to json object the deleteByProject query."))

    override def deleteByProject(project: ProjectRef): IO[Unit] =
      deleteByProjectQuery(project).flatMap { client.deleteByQuery(_, index) }

    private def deleteByResourceQuery(project: ProjectRef, id: Iri) = IO.fromOption(
      json"""
            {
              "query": {
                "bool": {
                  "must": [
                    { "term": { "project": ${project.asJson} } },
                    { "term": { "@id": ${id.asJson} } }
                  ]
                }
              }
            }""".asObject
    )(new IllegalStateException("Failed to convert to json object the deleteByProject query."))

    override def deleteByResource(project: ProjectRef, id: Iri): IO[Unit] =
      deleteByResourceQuery(project, id).flatMap { client.deleteByQuery(_, index) }

    private def historyQuery(project: ProjectRef, id: Iri) =
      IO.fromOption(json"""{
             "query": {
                "bool": {
                  "must": [
                    { "term": { "project": ${project.asJson} } },
                    { "term": { "@id": ${id.asJson} } }
                  ]
                }
              },
              "size": 2000,
              "from": 0,
              "sort": [
                  { "rev": { "order" : "asc" } }
              ]
            }
          """.asObject)(new IllegalStateException("Failed to convert to json object the deleteByResource query."))

    override def history(project: ProjectRef, id: Iri): IO[SearchResults[JsonObject]] = {
      for {
        jsonQuery   <- historyQuery(project, id)
        queryBuilder = QueryBuilder.unsafe(jsonQuery)
        results     <- client.search(queryBuilder, Set(index.value), Query.empty)
      } yield results
    }
  }

}
