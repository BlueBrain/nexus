package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.metrics

import akka.http.scaladsl.model.Uri
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.{ElasticSearchClient, IndexLabel, QueryBuilder}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import io.circe.JsonObject
import io.circe.literal.JsonStringContext
import io.circe.syntax.EncoderOps

trait EventMetricsQuery {

  def history(project: ProjectRef, id: Iri): IO[SearchResults[JsonObject]]

}

object EventMetricsQuery {

  def apply(client: ElasticSearchClient, index: IndexLabel): EventMetricsQuery = new EventMetricsQuery {

    private def searchQuery(project: ProjectRef, id: Iri) =
      json"""{
             "query": {
                "bool": {
                  "must": [
                    {
                      "term": {
                        "project": ${project.asJson}
                      }
                    },
                    {
                      "term": {
                        "@id": ${id.asJson}
                      }
                    }
                  ]
                }
            },
            "size": 2000,
            "from": 0,
             "sort": [
                { "rev": { "order" : "asc" } }
             ]
          }
          """.asObject.toRight(new IllegalStateException("Should not happen, an es query is an object"))

    override def history(project: ProjectRef, id: Iri): IO[SearchResults[JsonObject]] = {
      for {
        jsonQuery   <- IO.fromEither(searchQuery(project, id))
        queryBuilder = QueryBuilder.unsafe(jsonQuery)
        results     <- client.search(queryBuilder, Set(index.value), Uri.Query.Empty)
      } yield results
    }
  }

}
