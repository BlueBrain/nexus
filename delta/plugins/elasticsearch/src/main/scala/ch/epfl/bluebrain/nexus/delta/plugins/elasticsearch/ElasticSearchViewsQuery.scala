package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.kernel.database.Transactors
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.ElasticSearchClient
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewRejection.{AuthorizationFailed, InvalidResourceId, ViewIsDeprecated, ViewNotFound, WrappedElasticSearchClientError}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewValue.{AggregateElasticSearchViewValue, IndexingElasticSearchViewValue}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model._
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclAddress.{Project => ProjectAcl}
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.{Pagination, SearchResults, SortList}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, IdSegment}
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContext
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.{ApiMappings, ProjectBase, ProjectContext}
import ch.epfl.bluebrain.nexus.delta.sdk.views.View.{AggregateView, IndexingView}
import ch.epfl.bluebrain.nexus.delta.sdk.views.{ViewRef, ViewsStore}
import ch.epfl.bluebrain.nexus.delta.sourcing.Predicate
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef, ResourceRef}
import io.circe.{Json, JsonObject}
import monix.bio.IO

trait ElasticSearchViewsQuery {

  /**
    * Retrieves a list of resources from all the available default elasticsearch views using specific pagination, filter
    * and ordering configuration.
    *
    * @param pagination
    *   the pagination configuration
    * @param params
    *   the filtering configuration
    * @param sort
    *   the sorting configuration
    */
  def list(
      pagination: Pagination,
      params: ResourcesSearchParams,
      sort: SortList
  )(implicit caller: Caller): IO[ElasticSearchViewRejection, SearchResults[JsonObject]]

  /**
    * Retrieves a list of resources from all the available default elasticsearch views using specific pagination, filter
    * and ordering configuration.
    *
    * @param schema
    *   the schema where to search
    * @param pagination
    *   the pagination configuration
    * @param params
    *   the filtering configuration
    * @param sort
    *   the sorting configuration
    */
  def list(
      schema: IdSegment,
      pagination: Pagination,
      params: ResourcesSearchParams,
      sort: SortList
  )(implicit caller: Caller): IO[ElasticSearchViewRejection, SearchResults[JsonObject]]

  /**
    * Retrieves a list of resources from all the available default elasticsearch views inside the passed ''org'' using
    * specific pagination, filter and ordering configuration.
    *
    * @param org
    *   the organization
    * @param pagination
    *   the pagination configuration
    * @param params
    *   the filtering configuration
    * @param sort
    *   the sorting configuration
    */
  def list(
      org: Label,
      pagination: Pagination,
      params: ResourcesSearchParams,
      sort: SortList
  )(implicit caller: Caller): IO[ElasticSearchViewRejection, SearchResults[JsonObject]]

  /**
    * Retrieves a list of resources from all the available default elasticsearch views inside the passed ''org'' using
    * specific pagination, filter and ordering configuration.
    *
    * @param org
    *   the organization
    * @param schema
    *   the schema where to search
    * @param pagination
    *   the pagination configuration
    * @param params
    *   the filtering configuration
    * @param sort
    *   the sorting configuration
    */
  def list(
      org: Label,
      schema: IdSegment,
      pagination: Pagination,
      params: ResourcesSearchParams,
      sort: SortList
  )(implicit caller: Caller): IO[ElasticSearchViewRejection, SearchResults[JsonObject]]

  /**
    * Retrieves a list of resources from the default elasticsearch view using specific pagination, filter and ordering
    * configuration.
    *
    * @param project
    *   the project where to search
    * @param pagination
    *   the pagination configuration
    * @param params
    *   the filtering configuration
    * @param sort
    *   the sorting configuration
    */
  def list(
      project: ProjectRef,
      pagination: Pagination,
      params: ResourcesSearchParams,
      sort: SortList
  )(implicit caller: Caller): IO[ElasticSearchViewRejection, SearchResults[JsonObject]]

  /**
    * Retrieves a list of resources from the default elasticsearch view using specific pagination, filter and ordering
    * configuration. It will filter the resources with the passed ''schema''
    *
    * @param project
    *   the project where to search
    * @param schema
    *   the schema where to search
    * @param pagination
    *   the pagination configuration
    * @param params
    *   the filtering configuration
    * @param sort
    *   the sorting configuration
    */
  def list(
      project: ProjectRef,
      schema: IdSegment,
      pagination: Pagination,
      params: ResourcesSearchParams,
      sort: SortList
  )(implicit caller: Caller): IO[ElasticSearchViewRejection, SearchResults[JsonObject]]

  /**
    * Queries the elasticsearch index (or indices) managed by the view with the passed ''id''. We check for the caller
    * to have the necessary query permissions on the view before performing the query.
    *
    * @param id
    *   the id of the view either in Iri or aliased form
    * @param project
    *   the project where the view exists
    * @param query
    *   the elasticsearch query to run
    * @param qp
    *   the extra query parameters for the elasticsearch index
    */
  def query(
      id: IdSegment,
      project: ProjectRef,
      query: JsonObject,
      qp: Uri.Query
  )(implicit caller: Caller): IO[ElasticSearchViewRejection, Json]

}

/**
  * Operations that interact with the elasticsearch indices managed by ElasticSearchViews.
  */
final class ElasticSearchViewsQueryImpl private[elasticsearch] (
    viewStore: ViewsStore[ElasticSearchViewRejection],
    aclCheck: AclCheck,
    fetchContext: FetchContext[ElasticSearchViewRejection],
    client: ElasticSearchClient
)(implicit baseUri: BaseUri)
    extends ElasticSearchViewsQuery {

  override def list(
      pagination: Pagination,
      params: ResourcesSearchParams,
      sort: SortList
  )(implicit caller: Caller): IO[ElasticSearchViewRejection, SearchResults[JsonObject]] =
    list(Predicate.Root, pagination, params, sort)

  override def list(
      schema: IdSegment,
      pagination: Pagination,
      params: ResourcesSearchParams,
      sort: SortList
  )(implicit caller: Caller): IO[ElasticSearchViewRejection, SearchResults[JsonObject]] =
    for {
      schemeRef <- expandResourceRef(schema, fetchContext.defaultApiMappings, ProjectBase(iri""))
      search    <- list(pagination, params.withSchema(schemeRef), sort)
    } yield search

  override def list(
      org: Label,
      pagination: Pagination,
      params: ResourcesSearchParams,
      sort: SortList
  )(implicit caller: Caller): IO[ElasticSearchViewRejection, SearchResults[JsonObject]] =
    list(Predicate.Org(org), pagination, params, sort)

  private def list(
      predicate: Predicate,
      pagination: Pagination,
      params: ResourcesSearchParams,
      sort: SortList
  )(implicit caller: Caller): IO[ElasticSearchViewRejection, SearchResults[JsonObject]] =
    for {
      view              <- viewStore.fetchDefaultViews(predicate)
      accessibleIndices <- aclCheck.mapFilter[IndexingView, String](
                             view.views,
                             v => ProjectAcl(v.ref.project) -> permissions.read,
                             _.index
                           )
      search            <-
        client
          .search(params, accessibleIndices, Uri.Query.Empty)(pagination, sort)
          .mapError(WrappedElasticSearchClientError)
    } yield search

  override def list(
      org: Label,
      schema: IdSegment,
      pagination: Pagination,
      params: ResourcesSearchParams,
      sort: SortList
  )(implicit caller: Caller): IO[ElasticSearchViewRejection, SearchResults[JsonObject]] =
    for {
      schemeRef <- expandResourceRef(schema, fetchContext.defaultApiMappings, ProjectBase(iri""))
      search    <- list(org, pagination, params.withSchema(schemeRef), sort)
    } yield search

  override def list(
      project: ProjectRef,
      schema: IdSegment,
      pagination: Pagination,
      params: ResourcesSearchParams,
      sort: SortList
  )(implicit caller: Caller): IO[ElasticSearchViewRejection, SearchResults[JsonObject]] =
    for {
      projectContext <- fetchContext.onRead(project)
      schemeRef      <- expandResourceRef(schema, projectContext)
      search         <- list(project, pagination, params.withSchema(schemeRef), sort)
    } yield search

  override def list(
      project: ProjectRef,
      pagination: Pagination,
      params: ResourcesSearchParams,
      sort: SortList
  )(implicit caller: Caller): IO[ElasticSearchViewRejection, SearchResults[JsonObject]] =
    for {
      view   <- viewStore.fetchDefaultViews(Predicate.Project(project)).flatMap { agg =>
                  // Should not happened as default views are always created
                  IO.fromOption(agg.views.headOption, ViewNotFound(defaultViewId, project))
                }
      search <- client
                  .search(params, view.index, Uri.Query.Empty)(pagination, sort)
                  .mapError(WrappedElasticSearchClientError)
    } yield search

  def query(
      id: IdSegment,
      project: ProjectRef,
      query: JsonObject,
      qp: Uri.Query
  )(implicit caller: Caller): IO[ElasticSearchViewRejection, Json] = {
    for {
      view    <- viewStore.fetch(id, project)
      indices <- view match {
                   case v: IndexingView  =>
                     aclCheck.authorizeForOr(v.ref.project, v.permission)(AuthorizationFailed).as(Set(v.index))
                   case v: AggregateView =>
                     aclCheck.mapFilter[IndexingView, String](
                       v.views,
                       v => ProjectAcl(v.ref.project) -> v.permission,
                       _.index
                     )
                 }
      search  <- client.search(query, indices, qp)(SortList.empty).mapError(WrappedElasticSearchClientError)
    } yield search
  }

  private def expandResourceRef(
      segment: IdSegment,
      projectContext: ProjectContext
  ): IO[InvalidResourceId, ResourceRef] =
    expandResourceRef(segment, projectContext.apiMappings, projectContext.base)

  private def expandResourceRef(
      segment: IdSegment,
      mappings: ApiMappings,
      base: ProjectBase
  ): IO[InvalidResourceId, ResourceRef] =
    IO.fromOption(
      segment.toIri(mappings, base).map(ResourceRef(_)),
      InvalidResourceId(segment.asString)
    )

}

object ElasticSearchViewsQuery {

  final def apply(
      aclCheck: AclCheck,
      fetchContext: FetchContext[ElasticSearchViewRejection],
      views: ElasticSearchViews,
      client: ElasticSearchClient,
      prefix: String,
      xas: Transactors
  )(implicit baseUri: BaseUri): ElasticSearchViewsQuery =
    new ElasticSearchViewsQueryImpl(
      ViewsStore[ElasticSearchViewRejection, ElasticSearchViewState](
        ElasticSearchViews.entityType,
        ElasticSearchViewState.serializer,
        defaultViewId,
        views.fetchState(_, _).map(_._2),
        view =>
          IO.raiseWhen(view.deprecated)(ViewIsDeprecated(view.id)).as {
            view.value match {
              case _: AggregateElasticSearchViewValue =>
                Left(view.id)
              case i: IndexingElasticSearchViewValue  =>
                Right(
                  IndexingView(
                    ViewRef(view.project, view.id),
                    ElasticSearchViews.index(view.uuid, view.rev, prefix).value,
                    i.permission
                  )
                )
            }
          },
        xas
      ),
      aclCheck,
      fetchContext,
      client
    )
}
