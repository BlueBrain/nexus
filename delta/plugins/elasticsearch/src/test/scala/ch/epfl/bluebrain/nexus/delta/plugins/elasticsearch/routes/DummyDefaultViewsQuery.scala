package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.routes

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.search.Pagination.FromPagination
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.query.{DefaultSearchRequest, DefaultViewsQuery}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.routes.DummyDefaultViewsQuery._
import ch.epfl.bluebrain.nexus.delta.sdk.error.ServiceError.AuthorizationFailed
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.{AggregationResult, SearchResults}
import ch.epfl.bluebrain.nexus.testkit.CirceLiteral.circeLiteralSyntax
import io.circe.JsonObject

class DummyDefaultViewsQuery extends DefaultViewsQuery[Result, Aggregation] {

  override def list(
      searchRequest: DefaultSearchRequest
  )(implicit caller: Caller): IO[Result] =
    if (searchRequest.pagination == allowedPage)
      IO.pure(SearchResults(1, List(listResponse)))
    else
      IO.raiseError(AuthorizationFailed("Fail !!!!"))

  override def aggregate(searchRequest: DefaultSearchRequest)(implicit
      caller: Caller
  ): IO[Aggregation] =
    IO.pure(AggregationResult(1, aggregationResponse))
}

object DummyDefaultViewsQuery {
  type Result      = SearchResults[JsonObject]
  type Aggregation = AggregationResult

  private val allowedPage             = FromPagination(0, 5)
  val listResponse: JsonObject        = jobj"""{"http://localhost/projects": "all"}"""
  val aggregationResponse: JsonObject = jobj"""{"types": "something"}"""
}
