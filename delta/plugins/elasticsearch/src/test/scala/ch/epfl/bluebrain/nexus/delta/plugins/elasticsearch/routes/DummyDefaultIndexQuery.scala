package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.routes

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.search.Pagination.FromPagination
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.query.{DefaultIndexQuery, DefaultIndexRequest}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.routes.DummyDefaultIndexQuery._
import ch.epfl.bluebrain.nexus.delta.sdk.error.ServiceError.AuthorizationFailed
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.{AggregationResult, SearchResults}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.testkit.CirceLiteral.circeLiteralSyntax
import io.circe.JsonObject

class DummyDefaultIndexQuery extends DefaultIndexQuery {

  override def list(request: DefaultIndexRequest, projects: Set[ProjectRef]): IO[SearchResults[JsonObject]] =
    if (request.pagination == allowedPage)
      IO.pure(SearchResults(1, List(listResponse)))
    else
      IO.raiseError(AuthorizationFailed("Fail !!!!"))

  override def aggregate(request: DefaultIndexRequest, projects: Set[ProjectRef]): IO[AggregationResult] =
    IO.pure(AggregationResult(1, aggregationResponse))
}

object DummyDefaultIndexQuery {

  private val allowedPage             = FromPagination(0, 5)
  val listResponse: JsonObject        = jobj"""{"http://localhost/projects": "all"}"""
  val aggregationResponse: JsonObject = jobj"""{"types": "something"}"""
}
