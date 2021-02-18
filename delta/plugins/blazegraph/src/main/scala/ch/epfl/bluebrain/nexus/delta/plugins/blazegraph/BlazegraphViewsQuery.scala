package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph

import cats.data.NonEmptySet
import cats.syntax.foldable._
import cats.syntax.functor._
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.BlazegraphViewsQuery.VisitedView.{VisitedAggregatedView, VisitedIndexedView}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.BlazegraphViewsQuery.{FetchView, VisitedView}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.{BlazegraphClient, SparqlResults}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphView.{AggregateBlazegraphView, IndexingBlazegraphView}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewRejection.{AuthorizationFailed, WrappedBlazegraphClientError}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.{BlazegraphViewRejection, ViewRef, ViewResource}
import ch.epfl.bluebrain.nexus.delta.sdk.Acls
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegment
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegment.IriSegment
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclAddress.{Project => ProjectAcl}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.sourcing.config.ExternalIndexingConfig
import monix.bio.{IO, UIO}

/**
  * Operations that interact with the blazegraph namespaces managed by BlazegraphViews.
  */
class BlazegraphViewsQuery private[blazegraph] (
    fetchView: FetchView,
    acls: Acls,
    client: BlazegraphClient
)(implicit config: ExternalIndexingConfig) {

  /**
    * Queries the blazegraph namespace (or namespaces) managed by the view with the passed ''id''.
    * We check for the caller to have the necessary query permissions on the view before performing the query.
    *
    * @param id         the id of the view either in Iri or aliased form
    * @param project    the project where the view exists
    * @param query      the sparql query to run
    */
  def query(
      id: IdSegment,
      project: ProjectRef,
      query: String
  )(implicit caller: Caller): IO[BlazegraphViewRejection, SparqlResults] =
    fetchView(id, project).flatMap { view =>
      view.value match {
        case v: IndexingBlazegraphView  =>
          for {
            _      <- authorizeFor(v.project, v.permission)
            index   = view.as(v).index
            search <- client.query(Set(index), query).mapError(WrappedBlazegraphClientError)
          } yield search
        case v: AggregateBlazegraphView =>
          for {
            indices <- collectAccessibleIndices(v)
            search  <- client.query(indices, query).mapError(WrappedBlazegraphClientError)
          } yield search
      }
    }

  private def collectAccessibleIndices(view: AggregateBlazegraphView)(implicit caller: Caller) = {

    def visitOne(toVisit: ViewRef, visited: Set[VisitedView]): IO[BlazegraphViewRejection, Set[VisitedView]] =
      fetchView(IriSegment(toVisit.viewId), toVisit.project).flatMap { view =>
        view.value match {
          case v: AggregateBlazegraphView => visitAll(v.views, visited + VisitedAggregatedView(toVisit))
          case v: IndexingBlazegraphView  => IO.pure(Set(VisitedIndexedView(toVisit, view.as(v).index, v.permission)))
        }
      }

    def visitAll(
        toVisit: NonEmptySet[ViewRef],
        visited: Set[VisitedView] = Set.empty
    ): IO[BlazegraphViewRejection, Set[VisitedView]] =
      toVisit.foldLeftM(visited) {
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

object BlazegraphViewsQuery {

  sealed private[blazegraph] trait VisitedView {
    def ref: ViewRef
  }
  private[blazegraph] object VisitedView       {
    final case class VisitedIndexedView(ref: ViewRef, index: String, perm: Permission) extends VisitedView
    final case class VisitedAggregatedView(ref: ViewRef)                               extends VisitedView
  }

  private[blazegraph] type FetchView = (IdSegment, ProjectRef) => IO[BlazegraphViewRejection, ViewResource]

  final def apply(acls: Acls, views: BlazegraphViews, client: BlazegraphClient)(implicit
      config: ExternalIndexingConfig
  ): BlazegraphViewsQuery =
    new BlazegraphViewsQuery(views.fetch, acls, client)
}
