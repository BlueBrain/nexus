package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph

import cats.syntax.functor._
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClasspathResourceUtils.ioContentOf
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.{BlazegraphClient, SparqlQuery, SparqlResults}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphView.{AggregateBlazegraphView, IndexingBlazegraphView}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewRejection._
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.SparqlLink.{SparqlExternalLink, SparqlResourceLink}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model._
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.graph.NTriples
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.ExpandIri
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegment.IriSegment
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclAddress.{Project => ProjectAcl}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{ApiMappings, Project, ProjectBase, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.Pagination.FromPagination
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.ResultEntry.UnscoredResultEntry
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults.UnscoredSearchResults
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, IdSegment}
import ch.epfl.bluebrain.nexus.delta.sdk.views.ViewRefVisitor
import ch.epfl.bluebrain.nexus.delta.sdk.views.ViewRefVisitor.VisitedView.IndexedVisitedView
import ch.epfl.bluebrain.nexus.delta.sdk.{Acls, Projects}
import ch.epfl.bluebrain.nexus.delta.sourcing.config.ExternalIndexingConfig
import io.circe.Json
import monix.bio.IO

import java.util.regex.Pattern.quote
import scala.util.Try
import scala.xml.NodeSeq

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
    * Queries the blazegraph namespace (or namespaces) managed by the view with the passed ''id'',
    * returning the results in the sparql-results+json representation.
    * We check for the caller to have the necessary query permissions on the view before performing the query.
    *
    * @param id         the id of the view either in Iri or aliased form
    * @param project    the project where the view exists
    * @param query      the sparql query to run
    */
  def queryResults(
      id: IdSegment,
      project: ProjectRef,
      query: SparqlQuery
  )(implicit caller: Caller): IO[BlazegraphViewRejection, SparqlResults]

  /**
    * Queries the blazegraph namespace (or namespaces) managed by the view with the passed ''id'',
    * returning the results in the sparql-results+xml representation.
    * We check for the caller to have the necessary query permissions on the view before performing the query.
    *
    * @param id         the id of the view either in Iri or aliased form
    * @param project    the project where the view exists
    * @param query      the sparql query to run
    */
  def queryXml(
      id: IdSegment,
      project: ProjectRef,
      query: SparqlQuery
  )(implicit caller: Caller): IO[BlazegraphViewRejection, NodeSeq]

  /**
    * Queries the blazegraph namespace (or namespaces) managed by the view with the passed ''id'',
    * returning the results in the ld+json representation.
    * We check for the caller to have the necessary query permissions on the view before performing the query.
    *
    * @param id         the id of the view either in Iri or aliased form
    * @param project    the project where the view exists
    * @param query      the sparql query to run
    */
  def queryJsonLd(
      id: IdSegment,
      project: ProjectRef,
      query: SparqlQuery
  )(implicit caller: Caller): IO[BlazegraphViewRejection, Json]

  /**
    * Queries the blazegraph namespace (or namespaces) managed by the view with the passed ''id'',
    * returning the results in the n-triples representation.
    * We check for the caller to have the necessary query permissions on the view before performing the query.
    *
    * @param id         the id of the view either in Iri or aliased form
    * @param project    the project where the view exists
    * @param query      the sparql query to run
    */
  def queryNTriples(
      id: IdSegment,
      project: ProjectRef,
      query: SparqlQuery
  )(implicit caller: Caller): IO[BlazegraphViewRejection, NTriples]

  /**
    * Queries the blazegraph namespace (or namespaces) managed by the view with the passed ''id'',
    * returning the results in the rdf+xml representation.
    * We check for the caller to have the necessary query permissions on the view before performing the query.
    *
    * @param id         the id of the view either in Iri or aliased form
    * @param project    the project where the view exists
    * @param query      the sparql query to run
    */
  def queryRdfXml(
      id: IdSegment,
      project: ProjectRef,
      query: SparqlQuery
  )(implicit caller: Caller): IO[BlazegraphViewRejection, NodeSeq]

}

object BlazegraphViewsQuery {
  private[blazegraph] type FetchView    = (IdSegment, ProjectRef) => IO[BlazegraphViewRejection, ViewResource]
  private[blazegraph] type FetchProject = ProjectRef => IO[BlazegraphViewRejection, Project]

  final def apply(acls: Acls, views: BlazegraphViews, projects: Projects, client: BlazegraphClient)(implicit
      config: ExternalIndexingConfig
  ): BlazegraphViewsQuery =
    apply(
      views.fetch,
      BlazegraphViewRefVisitor(views, config),
      projects.fetchProject[BlazegraphViewRejection],
      acls,
      client
    )

  private[blazegraph] def apply(
      fetchView: FetchView,
      visitor: ViewRefVisitor[BlazegraphViewRejection],
      fetchProject: FetchProject,
      acls: Acls,
      client: BlazegraphClient
  )(implicit config: ExternalIndexingConfig): BlazegraphViewsQuery =
    new BlazegraphViewsQuery {

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
          bindings      <- queryResults(IriSegment(defaultViewId), projectRef, q)
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
        bindings      <- queryResults(IriSegment(defaultViewId), projectRef, q)
        links          = toSparqlLinks(bindings, p.apiMappings, p.base)
      } yield links

      def queryResults(
          id: IdSegment,
          project: ProjectRef,
          query: SparqlQuery
      )(implicit caller: Caller): IO[BlazegraphViewRejection, SparqlResults] =
        for {
          view    <- fetchView(id, project)
          indices <- accessibleNamespaces(view)
          qr      <- client.queryResults(indices, query).mapError(WrappedBlazegraphClientError)
        } yield qr

      def queryXml(
          id: IdSegment,
          project: ProjectRef,
          query: SparqlQuery
      )(implicit caller: Caller): IO[BlazegraphViewRejection, NodeSeq] =
        for {
          view    <- fetchView(id, project)
          indices <- accessibleNamespaces(view)
          qr      <- client.queryXml(indices, query).mapError(WrappedBlazegraphClientError)
        } yield qr

      def queryJsonLd(
          id: IdSegment,
          project: ProjectRef,
          query: SparqlQuery
      )(implicit caller: Caller): IO[BlazegraphViewRejection, Json] =
        for {
          view    <- fetchView(id, project)
          indices <- accessibleNamespaces(view)
          qr      <- client.queryJsonLd(indices, query).mapError(WrappedBlazegraphClientError)
        } yield qr

      def queryNTriples(
          id: IdSegment,
          project: ProjectRef,
          query: SparqlQuery
      )(implicit caller: Caller): IO[BlazegraphViewRejection, NTriples] =
        for {
          view    <- fetchView(id, project)
          indices <- accessibleNamespaces(view)
          qr      <- client.queryNTriples(indices, query).mapError(WrappedBlazegraphClientError)
        } yield qr

      def queryRdfXml(
          id: IdSegment,
          project: ProjectRef,
          query: SparqlQuery
      )(implicit caller: Caller): IO[BlazegraphViewRejection, NodeSeq] =
        for {
          view    <- fetchView(id, project)
          indices <- accessibleNamespaces(view)
          qr      <- client.queryRdfXml(indices, query).mapError(WrappedBlazegraphClientError)
        } yield qr

      private def accessibleNamespaces(
          view: ViewResource
      )(implicit caller: Caller): IO[BlazegraphViewRejection, Set[String]] =
        view.value match {

          case v: IndexingBlazegraphView =>
            acls
              .authorizeForOr(v.project, v.permission)(AuthorizationFailed)
              .as(Set(BlazegraphViews.namespace(view.as(v), config)))

          case v: AggregateBlazegraphView =>
            for {
              views         <- visitor.visitAll(v.views).map(_.collect { case v: IndexedVisitedView => v })
              access        <- acls.authorizeForAny(views.map(v => ProjectAcl(v.ref.project) -> v.permission))
              accessProjects = access.collect { case (p: ProjectAcl, true) => ProjectRef(p.org, p.project) }.toSet
            } yield views.collect { case v if accessProjects.contains(v.ref.project) => v.index }
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
    }
}
