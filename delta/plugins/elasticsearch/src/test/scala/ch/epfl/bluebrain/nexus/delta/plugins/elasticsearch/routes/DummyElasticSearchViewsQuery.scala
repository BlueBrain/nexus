package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.routes

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewRejection.{ViewIsDeprecated, ViewNotFound}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.{ElasticSearchViewRejection, ResourcesSearchParams}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.routes.DummyElasticSearchViewsQuery._
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.{ElasticSearchViews, ElasticSearchViewsQuery}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.Pagination.FromPagination
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.{Pagination, SearchResults, SortList}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, IdSegment, Label}
import ch.epfl.bluebrain.nexus.testkit.CirceLiteral._
import io.circe.syntax._
import io.circe.{Json, JsonObject}
import monix.bio.IO

private[routes] class DummyElasticSearchViewsQuery(views: ElasticSearchViews) extends ElasticSearchViewsQuery {

  private def toJsonObject(value: Map[String, String]) =
    JsonObject.fromMap(value.map { case (k, v) => k -> v.asJson })

  private val allowedPage                              = FromPagination(0, 5)
  private val allowedSearchParams                      = ResourcesSearchParams(q = Some("something"))

  override def list(
      pagination: Pagination,
      params: ResourcesSearchParams,
      sort: SortList
  )(implicit caller: Caller, baseUri: BaseUri): IO[ElasticSearchViewRejection, SearchResults[JsonObject]] =
    if (pagination == allowedPage && params == allowedSearchParams)
      IO.pure(SearchResults(1, List(listResponse)))
    else
      IO.raiseError(ViewNotFound(nxv + "id", ProjectRef.unsafe("any", "any")))

  override def list(
      schema: IdSegment,
      pagination: Pagination,
      params: ResourcesSearchParams,
      sort: SortList
  )(implicit caller: Caller, baseUri: BaseUri): IO[ElasticSearchViewRejection, SearchResults[JsonObject]] =
    if (pagination == allowedPage && params == allowedSearchParams)
      IO.pure(SearchResults(1, List(listResponse(schema))))
    else
      IO.raiseError(ViewNotFound(nxv + "id", ProjectRef.unsafe("any", "any")))

  override def list(
      org: Label,
      pagination: Pagination,
      params: ResourcesSearchParams,
      sort: SortList
  )(implicit caller: Caller, baseUri: BaseUri): IO[ElasticSearchViewRejection, SearchResults[JsonObject]] =
    if (pagination == allowedPage && params == allowedSearchParams)
      IO.pure(SearchResults(1, List(listResponse(org))))
    else
      IO.raiseError(ViewNotFound(nxv + "id", ProjectRef.unsafe(org.value, "any")))

  override def list(
      org: Label,
      schema: IdSegment,
      pagination: Pagination,
      params: ResourcesSearchParams,
      sort: SortList
  )(implicit caller: Caller, baseUri: BaseUri): IO[ElasticSearchViewRejection, SearchResults[JsonObject]] =
    if (pagination == allowedPage && params == allowedSearchParams)
      IO.pure(SearchResults(1, List(listResponse(org, schema))))
    else
      IO.raiseError(ViewNotFound(nxv + "id", ProjectRef.unsafe(org.value, "any")))

  override def list(
      project: ProjectRef,
      pagination: Pagination,
      params: ResourcesSearchParams,
      sort: SortList
  )(implicit caller: Caller, baseUri: BaseUri): IO[ElasticSearchViewRejection, SearchResults[JsonObject]] =
    if (pagination == allowedPage && params == allowedSearchParams)
      IO.pure(SearchResults(1, List(listResponse(project))))
    else
      IO.raiseError(ViewNotFound(nxv + "id", project))

  override def list(
      project: ProjectRef,
      schema: IdSegment,
      pagination: Pagination,
      params: ResourcesSearchParams,
      sort: SortList
  )(implicit caller: Caller, baseUri: BaseUri): IO[ElasticSearchViewRejection, SearchResults[JsonObject]] =
    if (pagination == allowedPage && params == allowedSearchParams) {
      IO.pure(SearchResults(1, List(listResponse(project, schema))))

    } else
      IO.raiseError(ViewNotFound(nxv + "id", project))

  override def query(
      id: IdSegment,
      project: ProjectRef,
      query: JsonObject,
      qp: Uri.Query
  )(implicit caller: Caller): IO[ElasticSearchViewRejection, Json] = {
    for {
      view <- views.fetch(id, project)
      _    <- IO.raiseWhen(view.deprecated)(ViewIsDeprecated(view.id))
    } yield json"""{"id": "$id", "project": "$project"}""" deepMerge toJsonObject(
      qp.toMap
    ).asJson deepMerge query.asJson
  }
}

object DummyElasticSearchViewsQuery {

  val listResponse: JsonObject = jobj"""{"http://localhost/projects": "all"}"""

  def listResponse(schema: IdSegment): JsonObject =
    jobj"""{"http://localhost/projects": "all", "http://localhost/schema": "${schema.asString}"}"""

  def listResponse(org: Label): JsonObject =
    jobj"""{"http://localhost/projects": "all", "http://localhost/org": "${org}"}"""

  def listResponse(org: Label, schema: IdSegment): JsonObject =
    jobj"""{"http://localhost/projects": "all", "http://localhost/org": "${org}", "http://localhost/schema": "${schema.asString}"}"""

  def listResponse(projectRef: ProjectRef): JsonObject =
    jobj"""{"http://localhost/projects": "all", "http://localhost/project": "$projectRef"}"""

  def listResponse(projectRef: ProjectRef, schema: IdSegment): JsonObject =
    jobj"""{"http://localhost/projects": "all", "http://localhost/project": "$projectRef", "http://localhost/schema": "${schema.asString}"}"""
}
