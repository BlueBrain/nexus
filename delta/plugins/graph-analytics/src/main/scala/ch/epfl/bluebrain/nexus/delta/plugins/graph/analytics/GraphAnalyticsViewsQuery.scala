package ch.epfl.bluebrain.nexus.delta.plugins.graph.analytics

import akka.http.scaladsl.model.Uri
import cats.effect.IO
import cats.implicits.catsSyntaxMonadError
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.ElasticSearchClient
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewRejection.WrappedElasticSearchClientError
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClientError
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SortList
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import io.circe.{Json, JsonObject}

/** Allows to perform elasticsearch queries on Graph Analytics views */
trait GraphAnalyticsViewsQuery {

  /**
    * In a given project, perform the provided elasticsearch query on the projects' Graph Analytics view.
    * @param projectRef
    *   project in which to make the query
    * @param query
    *   elasticsearch query to perform on the Graph Analytics view
    * @param qp
    *   the extra query parameters for the elasticsearch index
    */
  def query(projectRef: ProjectRef, query: JsonObject, qp: Uri.Query): IO[Json]
}

/**
  * A [[GraphAnalyticsViewsQuery]] implementation that uses the [[ElasticSearchClient]] to query views.
  * @param prefix
  *   prefix used in the names of the elasticsearch indices
  * @param client
  *   elasticsearch client
  */
class GraphAnalyticsViewsQueryImpl(prefix: String, client: ElasticSearchClient) extends GraphAnalyticsViewsQuery {
  override def query(projectRef: ProjectRef, query: JsonObject, qp: Uri.Query): IO[Json] = {
    val index = GraphAnalytics.index(prefix, projectRef)
    client.search(query, Set(index.value), qp)(SortList.empty).adaptError { case e: HttpClientError =>
      WrappedElasticSearchClientError(e)
    }
  }

}
