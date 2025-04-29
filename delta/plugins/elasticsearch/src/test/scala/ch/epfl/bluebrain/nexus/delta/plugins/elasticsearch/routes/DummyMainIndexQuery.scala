package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.routes

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.search.Pagination.FromPagination
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.query.{MainIndexQuery, MainIndexRequest}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.routes.DummyMainIndexQuery.*
import ch.epfl.bluebrain.nexus.delta.sdk.error.ServiceError.AuthorizationFailed
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.{AggregationResult, SearchResults}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.testkit.CirceLiteral.circeLiteralSyntax
import io.circe.{Json, JsonObject}
import org.http4s.Query

class DummyMainIndexQuery extends MainIndexQuery {

  override def search(project: ProjectRef, query: JsonObject, qp: Query): IO[Json] =
    IO.raiseError(AuthorizationFailed("Fail !!!!"))

  override def list(request: MainIndexRequest, projects: Set[ProjectRef]): IO[SearchResults[JsonObject]] =
    if (request.pagination == allowedPage)
      IO.pure(SearchResults(1, List(listResponse)))
    else
      IO.raiseError(AuthorizationFailed("Fail !!!!"))

  override def aggregate(request: MainIndexRequest, projects: Set[ProjectRef]): IO[AggregationResult] =
    IO.pure(AggregationResult(1, aggregationResponse))
}

object DummyMainIndexQuery {

  private val allowedPage             = FromPagination(0, 5)
  val listResponse: JsonObject        = jobj"""{"http://localhost/projects": "all"}"""
  val aggregationResponse: JsonObject = jobj"""{"types": "something"}"""
}
