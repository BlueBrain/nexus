package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph

import cats.syntax.foldable._
import cats.syntax.functor._
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClasspathResourceUtils.ioContentOf
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.BlazegraphViewsQuery.VisitedView.{VisitedAggregatedView, VisitedIndexedView}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.BlazegraphViewsQuery.{FetchProject, FetchView, VisitedView}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.{BlazegraphClient, SparqlQuery, SparqlResults}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphView.{AggregateBlazegraphView, IndexingBlazegraphView}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewRejection.{AuthorizationFailed, WrappedBlazegraphClientError, _}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.SparqlLink.{SparqlExternalLink, SparqlResourceLink}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.{BlazegraphViewRejection, ViewRef, ViewResource, _}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.ExpandIri
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegment.IriSegment
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclAddress.{Project => ProjectAcl}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{ApiMappings, Project, ProjectBase, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.Pagination.FromPagination
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.ResultEntry.UnscoredResultEntry
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults.UnscoredSearchResults
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, IdSegment, NonEmptySet}
import ch.epfl.bluebrain.nexus.delta.sdk.{Acls, Projects}
import ch.epfl.bluebrain.nexus.delta.sourcing.config.ExternalIndexingConfig
import monix.bio.{IO, UIO}

import java.util.regex.Pattern.quote
import scala.util.Try

trait BlazegraphViewsQuery {

  /**
    * List incoming links for a given resource.
    *
    * @param id         the resource identifier
    * @param projectRef the project of the resource
    * @param pagination the pagination config
    */
  def incoming(
      id: IdSegment,
      projectRef: ProjectRef,
      pagination: FromPagination
  )(implicit caller: Caller, base: BaseUri): IO[BlazegraphViewRejection, SearchResults[SparqlLink]]

  /**
    * List outgoing links for a given resource.
    *
    * @param id                   the resource identifier
    * @param projectRef           the project of the resource
    * @param pagination           the pagination config
    * @param includeExternalLinks whether to include links to resources not managed by Delta
    */
  def outgoing(
      id: IdSegment,
      projectRef: ProjectRef,
      pagination: FromPagination,
      includeExternalLinks: Boolean
  )(implicit caller: Caller, base: BaseUri): IO[BlazegraphViewRejection, SearchResults[SparqlLink]]

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
      query: SparqlQuery
  )(implicit caller: Caller): IO[BlazegraphViewRejection, SparqlResults]

}

/**
  * Operations that interact with the blazegraph namespaces managed by BlazegraphViews.
  */
final class BlazegraphViewsQueryImpl private[blazegraph] (
    fetchView: FetchView,
    fetchProject: FetchProject,
    acls: Acls,
    client: BlazegraphClient
)(implicit config: ExternalIndexingConfig)
    extends BlazegraphViewsQuery {

  private val expandIri: ExpandIri[BlazegraphViewRejection] = new ExpandIri(InvalidResourceId.apply)

  implicit private val cl: ClassLoader  = this.getClass.getClassLoader
  private val incomingQuery             =
    ioContentOf("blazegraph/incoming.txt").mapError(WrappedClasspathResourceError).memoizeOnSuccess
  private val outgoingWithExternalQuery =
    ioContentOf("blazegraph/outgoing_include_external.txt").mapError(WrappedClasspathResourceError).memoizeOnSuccess
  private val outgoingScopedQuery       =
    ioContentOf("blazegraph/outgoing_scoped.txt").mapError(WrappedClasspathResourceError).memoizeOnSuccess

  private def replace(query: String, id: Iri, pagination: FromPagination): String = {
    query
      .replaceAll(quote("{id}"), id.toString)
      .replaceAll(quote("{graph}"), (id / "graph").toString)
      .replaceAll(quote("{offset}"), pagination.from.toString)
      .replaceAll(quote("{size}"), pagination.size.toString)
  }

  private def toSparqlLinks(sparqlResults: SparqlResults, mappings: ApiMappings, projectBase: ProjectBase)(implicit
      base: BaseUri
  ): SearchResults[SparqlLink] = {
    val (count, results) =
      sparqlResults.results.bindings
        .foldLeft((0L, List.empty[SparqlLink])) { case ((total, acc), bindings) =>
          val newTotal = bindings.get("total").flatMap(v => Try(v.value.toLong).toOption).getOrElse(total)
          val res      = (SparqlResourceLink(bindings, mappings, projectBase) orElse SparqlExternalLink(bindings))
            .map(_ :: acc)
            .getOrElse(acc)
          (newTotal, res)
        }
    UnscoredSearchResults(count, results.map(UnscoredResultEntry(_)))
  }

  def incoming(
      id: IdSegment,
      projectRef: ProjectRef,
      pagination: FromPagination
  )(implicit
      caller: Caller,
      base: BaseUri
  ): IO[BlazegraphViewRejection, SearchResults[SparqlLink]] =
    for {
      queryTemplate <- incomingQuery
      p             <- fetchProject(projectRef)
      iri           <- expandIri(id, p)
      q              = SparqlQuery(replace(queryTemplate, iri, pagination))
      bindings      <- query(IriSegment(defaultViewId), projectRef, q)
      links          = toSparqlLinks(bindings, p.apiMappings, p.base)
    } yield links

  def outgoing(
      id: IdSegment,
      projectRef: ProjectRef,
      pagination: FromPagination,
      includeExternalLinks: Boolean
  )(implicit caller: Caller, base: BaseUri): IO[BlazegraphViewRejection, SearchResults[SparqlLink]] = for {
    queryTemplate <- if (includeExternalLinks) outgoingWithExternalQuery else outgoingScopedQuery
    p             <- fetchProject(projectRef)
    iri           <- expandIri(id, p)
    q              = SparqlQuery(replace(queryTemplate, iri, pagination))
    bindings      <- query(IriSegment(defaultViewId), projectRef, q)
    links          = toSparqlLinks(bindings, p.apiMappings, p.base)
  } yield links

  def query(
      id: IdSegment,
      project: ProjectRef,
      query: SparqlQuery
  )(implicit caller: Caller): IO[BlazegraphViewRejection, SparqlResults] =
    fetchView(id, project).flatMap { view =>
      view.value match {
        case v: IndexingBlazegraphView  =>
          for {
            _      <- authorizeFor(v.project, v.permission)
            index   = BlazegraphViews.index(view.as(v), config)
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
      fetchView(toVisit.viewId, toVisit.project).flatMap { view =>
        view.value match {
          case v: AggregateBlazegraphView => visitAll(v.views, visited + VisitedAggregatedView(toVisit))
          case v: IndexingBlazegraphView  =>
            IO.pure(Set(VisitedIndexedView(toVisit, BlazegraphViews.index(view.as(v), config), v.permission)))
        }
      }

    def visitAll(
        toVisit: NonEmptySet[ViewRef],
        visited: Set[VisitedView] = Set.empty
    ): IO[BlazegraphViewRejection, Set[VisitedView]] =
      toVisit.value.toList.foldM(visited) {
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

  private[blazegraph] type FetchView    = (IdSegment, ProjectRef) => IO[BlazegraphViewRejection, ViewResource]
  private[blazegraph] type FetchProject = ProjectRef => IO[BlazegraphViewRejection, Project]

  final def apply(acls: Acls, views: BlazegraphViews, projects: Projects, client: BlazegraphClient)(implicit
      config: ExternalIndexingConfig
  ): BlazegraphViewsQuery =
    new BlazegraphViewsQueryImpl(views.fetch, projects.fetchProject[BlazegraphViewRejection], acls, client)
}
