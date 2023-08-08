package ch.epfl.bluebrain.nexus.delta.plugins.graph.analytics

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.ElasticSearchClient
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewRejection
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewRejection.WrappedElasticSearchClientError
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SortList
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import io.circe.{Json, JsonObject}
import monix.bio.IO

trait GraphAnalyticsViewsQuery {
  def query(projectRef: ProjectRef, query: JsonObject, qp: Uri.Query): IO[ElasticSearchViewRejection, Json]
}

class GraphAnalyticsViewsQueryImpl(prefix: String, client: ElasticSearchClient) extends GraphAnalyticsViewsQuery {
  override def query(projectRef: ProjectRef, query: JsonObject, qp: Uri.Query): IO[ElasticSearchViewRejection, Json] = {
    val index = GraphAnalytics.index(prefix, projectRef)
    client.search(query, Set(index.value), qp)(SortList.empty).mapError(WrappedElasticSearchClientError)
  }

}
