package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch

import akka.http.scaladsl.model.Uri
import cats.syntax.functor._
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.ElasticSearchViewsQuery.{FetchDefaultView, FetchView}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.ElasticSearchClient
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchView.{AggregateElasticSearchView, IndexingElasticSearchView}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewRejection.{projectToElasticSearchRejectionMapper, AuthorizationFailed, InvalidResourceId, ViewIsDeprecated, WrappedElasticSearchClientError}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model._
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclAddress.{Project => ProjectAcl}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectFetchOptions.{notDeprecated}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{Project, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.{Pagination, SearchResults, SortList}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, IdSegment, IdSegmentRef, ResourceRef}
import ch.epfl.bluebrain.nexus.delta.sdk.views.ViewRefVisitor
import ch.epfl.bluebrain.nexus.delta.sdk.views.ViewRefVisitor.VisitedView.IndexedVisitedView
import ch.epfl.bluebrain.nexus.delta.sdk.{Acls, Projects}
import ch.epfl.bluebrain.nexus.delta.sourcing.config.ExternalIndexingConfig
import io.circe.{Json, JsonObject}
import monix.bio.IO

trait ElasticSearchViewsQuery {

  /**
    * Retrieves a list of resources from the default elasticsearch view using specific pagination, filter and ordering configuration.
    *
    * @param project    the project where to search
    * @param pagination the pagination configuration
    * @param params     the filtering configuration
    * @param sort       the sorting configuration
    */
  def list(
      project: ProjectRef,
      pagination: Pagination,
      params: ResourcesSearchParams,
      sort: SortList
  )(implicit caller: Caller, baseUri: BaseUri): IO[ElasticSearchViewRejection, SearchResults[JsonObject]]

  /**
    * Retrieves a list of resources from the default elasticsearch view using specific pagination, filter and ordering configuration.
    * It will filter the resources with the passed ''schema''
    *
    * @param project    the project where to search
    * @param schema     the schema where to search
    * @param pagination the pagination configuration
    * @param params     the filtering configuration
    * @param sort       the sorting configuration
    */
  def list(
      project: ProjectRef,
      schema: IdSegment,
      pagination: Pagination,
      params: ResourcesSearchParams,
      sort: SortList
  )(implicit caller: Caller, baseUri: BaseUri): IO[ElasticSearchViewRejection, SearchResults[JsonObject]]

  /**
    * Queries the elasticsearch index (or indices) managed by the view with the passed ''id''.
    * We check for the caller to have the necessary query permissions on the view before performing the query.
    *
    * @param id         the id of the view either in Iri or aliased form
    * @param project    the project where the view exists
    * @param query      the elasticsearch query to run
    * @param qp         the extra query parameters for the elasticsearch index
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
    fetchDefaultView: FetchDefaultView,
    fetchView: FetchView,
    visitor: ViewRefVisitor[ElasticSearchViewRejection],
    acls: Acls,
    projects: Projects,
    client: ElasticSearchClient
)(implicit config: ExternalIndexingConfig)
    extends ElasticSearchViewsQuery {

  override def list(
      project: ProjectRef,
      schema: IdSegment,
      pagination: Pagination,
      params: ResourcesSearchParams,
      sort: SortList
  )(implicit caller: Caller, baseUri: BaseUri): IO[ElasticSearchViewRejection, SearchResults[JsonObject]] =
    for {
      projectValue <- projects.fetchProject(project, notDeprecated)
      view         <- fetchDefaultView(project)
      schemeRef    <- expandResourceRef(schema, projectValue)
      p             = params.withSchema(schemeRef)
      search       <- client
                        .search(p, ElasticSearchViews.index(view, config), Uri.Query.Empty)(pagination, sort)
                        .mapError(WrappedElasticSearchClientError)
    } yield search

  def list(
      project: ProjectRef,
      pagination: Pagination,
      params: ResourcesSearchParams,
      sort: SortList
  )(implicit caller: Caller, baseUri: BaseUri): IO[ElasticSearchViewRejection, SearchResults[JsonObject]] =
    for {
      view   <- fetchDefaultView(project)
      search <- client
                  .search(params, ElasticSearchViews.index(view, config), Uri.Query.Empty)(pagination, sort)
                  .mapError(WrappedElasticSearchClientError)
    } yield search

  def query(
      id: IdSegment,
      project: ProjectRef,
      query: JsonObject,
      qp: Uri.Query
  )(implicit caller: Caller): IO[ElasticSearchViewRejection, Json] =
    fetchView(id, project).flatMap { view =>
      view.value match {
        case v: IndexingElasticSearchView  =>
          for {
            _      <- acls.authorizeForOr(v.project, v.permission)(AuthorizationFailed)
            _      <- IO.raiseWhen(view.deprecated)(ViewIsDeprecated(v.id))
            index   = ElasticSearchViews.index(view.as(v), config)
            search <- client.search(query, Set(index), qp)(SortList.empty).mapError(WrappedElasticSearchClientError)
          } yield search
        case v: AggregateElasticSearchView =>
          for {
            _       <- IO.raiseWhen(view.deprecated)(ViewIsDeprecated(v.id))
            indices <- collectAccessibleIndices(v)
            search  <-
              client.search(query, indices, qp)(SortList.empty).mapError(WrappedElasticSearchClientError)
          } yield search
      }
    }

  private def collectAccessibleIndices(view: AggregateElasticSearchView)(implicit caller: Caller) = {

    for {
      views             <- visitor.visitAll(view.views).map(_.collect { case v: IndexedVisitedView => v })
      accessible        <- acls.authorizeForAny(views.map(v => ProjectAcl(v.ref.project) -> v.permission))
      accessibleProjects = accessible.collect { case (p: ProjectAcl, true) => ProjectRef(p.org, p.project) }.toSet
    } yield views.collect { case v if accessibleProjects.contains(v.ref.project) => v.index }
  }

  private def expandResourceRef(segment: IdSegment, project: Project): IO[InvalidResourceId, ResourceRef] =
    IO.fromOption(
      segment.toIri(project.apiMappings, project.base).map(ResourceRef(_)),
      InvalidResourceId(segment.asString)
    )

}

object ElasticSearchViewsQuery {

  private[elasticsearch] type FetchDefaultView = ProjectRef => IO[ElasticSearchViewRejection, IndexingViewResource]
  private[elasticsearch] type FetchView        = (IdSegmentRef, ProjectRef) => IO[ElasticSearchViewRejection, ViewResource]

  final def apply(
      acls: Acls,
      projects: Projects,
      views: ElasticSearchViews,
      client: ElasticSearchClient
  )(implicit
      config: ExternalIndexingConfig
  ): ElasticSearchViewsQuery =
    new ElasticSearchViewsQueryImpl(
      views.fetchIndexingView(defaultViewId, _),
      views.fetch,
      ElasticSearchViewRefVisitor(views, config),
      acls,
      projects,
      client
    )
}
