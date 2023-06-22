package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.routes

import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ResourcesSearchParams
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.query.{DefaultSearchRequest, DefaultViewsQuery, ElasticSearchQueryError}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.routes.DummyDefaultViewsQuery._
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.Pagination.FromPagination
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.{AggregationResult, SearchResults}
import ch.epfl.bluebrain.nexus.testkit.CirceLiteral.circeLiteralSyntax
import io.circe.JsonObject
import monix.bio.IO

class DummyDefaultViewsQuery extends DefaultViewsQuery[Result, Aggregation] {

  // TODO: Correct this dummy method
  override def list(searchRequest: DefaultSearchRequest)(implicit caller: Caller): IO[ElasticSearchQueryError, Result] =
    if (searchRequest.pagination == allowedPage && searchRequest.params == allowedSearchParams)
      IO.pure(SearchResults(1, List(listResponse)))
    else
      IO.raiseError(ElasticSearchQueryError.AuthorizationFailed)

  // TODO: Correct this dummy method
  override def aggregate(searchRequest: DefaultSearchRequest)(implicit
      caller: Caller
  ): IO[ElasticSearchQueryError, Aggregation] =
    IO.pure(AggregationResult(10, jobj"""{"field": "something"}"""))
}

object DummyDefaultViewsQuery {

  type Result      = SearchResults[JsonObject]
  type Aggregation = AggregationResult

  private val allowedPage         = FromPagination(0, 5)
  private val allowedSearchParams = ResourcesSearchParams(q = Some("something"))

  val listResponse: JsonObject = jobj"""{"http://localhost/projects": "all"}"""
}
