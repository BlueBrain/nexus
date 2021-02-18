package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch

import akka.http.scaladsl.model.Uri
import cats.data.NonEmptySet
import cats.syntax.foldable._
import cats.syntax.functor._
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.ElasticSearchViewsQuery.VisitedView.{VisitedAggregatedView, VisitedIndexedView}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.ElasticSearchViewsQuery.{FetchDefaultView, FetchView, VisitedView}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.ElasticSearchClient
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchView.{AggregateElasticSearchView, IndexingElasticSearchView}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewRejection.{AuthorizationFailed, WrappedElasticSearchClientError}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model._
import ch.epfl.bluebrain.nexus.delta.sdk.Acls
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegment.IriSegment
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclAddress.{Project => ProjectAcl}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.{Pagination, SearchResults, SortList}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, IdSegment}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.sourcing.config.ExternalIndexingConfig
import io.circe.{Json, JsonObject}
import monix.bio.{IO, UIO}

/**
  * Operations that interact with the elasticsearch indices managed by ElasticSearchViews.
  */
class ElasticSearchViewsQuery private[elasticsearch] (
    fetchDefaultView: FetchDefaultView,
    fetchView: FetchView,
    acls: Acls,
    client: ElasticSearchClient
)(implicit config: ExternalIndexingConfig) {

  /**
    * Retrieves a list of resources from the default elasticsearch view using specific pagination, filter and ordering configuration.
    *
    * @param project    the project where to search
    * @param pagination the pagination configuration
    * @param params     the filtering configuration
    * @param qp         the extra query parameters for the elasticsearch view
    * @param sort       the sorting configuration
    */
  def list(
      project: ProjectRef,
      pagination: Pagination,
      params: ResourcesSearchParams,
      qp: Uri.Query,
      sort: SortList
  )(implicit caller: Caller, baseUri: BaseUri): IO[ElasticSearchViewRejection, SearchResults[JsonObject]] =
    for {
      view   <- fetchDefaultView(project)
      _      <- authorizeFor(project, view.value.permission)
      search <- client.search(params, Set(view.index), qp)(pagination, sort).mapError(WrappedElasticSearchClientError)
    } yield search

  /**
    * Queries the elasticsearch index (or indices) managed by the view with the passed ''id''.
    * We check for the caller to have the necessary query permissions on the view before performing the query.
    *
    * @param id         the id of the view either in Iri or aliased form
    * @param project    the project where the view exists
    * @param pagination the pagination configuration
    * @param query      the elasticsearch query to run
    * @param qp         the extra query parameters for the elasticsearch index
    * @param sort       the sorting configuration
    */
  def query(
      id: IdSegment,
      project: ProjectRef,
      pagination: Pagination,
      query: JsonObject,
      qp: Uri.Query,
      sort: SortList
  )(implicit caller: Caller): IO[ElasticSearchViewRejection, Json] =
    fetchView(id, project).flatMap { view =>
      view.value match {
        case v: IndexingElasticSearchView  =>
          for {
            _      <- authorizeFor(v.project, v.permission)
            index   = view.as(v).index
            search <- client.search(query, Set(index), qp)(pagination, sort).mapError(WrappedElasticSearchClientError)
          } yield search
        case v: AggregateElasticSearchView =>
          for {
            indices <- collectAccessibleIndices(v)
            search  <-
              client.search(query, indices, qp)(pagination, sort).mapError(WrappedElasticSearchClientError)
          } yield search
      }
    }

  private def collectAccessibleIndices(view: AggregateElasticSearchView)(implicit caller: Caller) = {

    def visitOne(toVisit: ViewRef, visited: Set[VisitedView]): IO[ElasticSearchViewRejection, Set[VisitedView]] =
      fetchView(IriSegment(toVisit.viewId), toVisit.project).flatMap { view =>
        view.value match {
          case v: AggregateElasticSearchView => visitAll(v.views, visited + VisitedAggregatedView(toVisit))
          case v: IndexingElasticSearchView  => IO.pure(Set(VisitedIndexedView(toVisit, view.as(v).index, v.permission)))
        }
      }

    def visitAll(
        toVisit: NonEmptySet[ViewRef],
        visited: Set[VisitedView] = Set.empty
    ): IO[ElasticSearchViewRejection, Set[VisitedView]] =
      toVisit.foldM(visited) {
        case (visited, viewToVisit) if visited.exists(_.ref == viewToVisit) => UIO.pure(visited)
        case (visited, viewToVisit)                                         => visitOne(viewToVisit, visited).map(visited ++ _)
      }

    for {
      views             <- visitAll(view.views).map(_.collect { case v: VisitedIndexedView => v })
      accessible        <- acls.authorizeForAny(views.map(v => ProjectAcl(v.ref.project) -> v.perm))
      accessibleProjects = accessible.collect { case (p: ProjectAcl, true) => ProjectRef(p.org, p.project) }.toSet
    } yield views.collect { case v if accessibleProjects.contains(v.ref.project) => v.index }
  }

  private def authorizeFor(
      projectRef: ProjectRef,
      permission: Permission
  )(implicit caller: Caller): IO[AuthorizationFailed, Unit] =
    acls.authorizeFor(ProjectAcl(projectRef), permission).flatMap { hasAccess =>
      IO.unless(hasAccess)(IO.raiseError(AuthorizationFailed))
    }
}

object ElasticSearchViewsQuery {

  sealed private[elasticsearch] trait VisitedView {
    def ref: ViewRef
  }
  private[elasticsearch] object VisitedView       {
    final case class VisitedIndexedView(ref: ViewRef, index: String, perm: Permission) extends VisitedView
    final case class VisitedAggregatedView(ref: ViewRef)                               extends VisitedView
  }

  private[elasticsearch] type FetchDefaultView = ProjectRef => IO[ElasticSearchViewRejection, IndexingViewResource]
  private[elasticsearch] type FetchView        = (IdSegment, ProjectRef) => IO[ElasticSearchViewRejection, ViewResource]

  final def apply(acls: Acls, views: ElasticSearchViews, client: ElasticSearchClient)(implicit
      config: ExternalIndexingConfig
  ): ElasticSearchViewsQuery =
    new ElasticSearchViewsQuery(views.fetchIndexingView(IriSegment(defaultViewId), _), views.fetch, acls, client)
}
