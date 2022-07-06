package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph

import cats.syntax.functor._
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClasspathResourceUtils.ioContentOf
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.SparqlQueryResponseType.SparqlResultsJson
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client._
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphView.{AggregateBlazegraphView, IndexingBlazegraphView}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewRejection._
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.SparqlLink.{SparqlExternalLink, SparqlResourceLink}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model._
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.query.SparqlQuery
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclAddress.{Project => ProjectAcl}
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.ExpandIri
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegment.IriSegment
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.Pagination.FromPagination
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.ResultEntry.UnscoredResultEntry
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults.UnscoredSearchResults
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, IdSegment, IdSegmentRef}
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContext
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.{ApiMappings, ProjectBase}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.views.ViewRefVisitor
import ch.epfl.bluebrain.nexus.delta.sdk.views.ViewRefVisitor.VisitedView.IndexedVisitedView
import ch.epfl.bluebrain.nexus.delta.sourcing.config.ExternalIndexingConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import com.typesafe.scalalogging.Logger
import monix.bio.IO

import java.util.regex.Pattern.quote
import scala.util.Try

trait BlazegraphViewsQuery {

  /**
    * List incoming links for a given resource.
    *
    * @param id
    *   the resource identifier
    * @param projectRef
    *   the project of the resource
    * @param pagination
    *   the pagination config
    */
  def incoming(
      id: IdSegment,
      projectRef: ProjectRef,
      pagination: FromPagination
  )(implicit caller: Caller, base: BaseUri): IO[BlazegraphViewRejection, SearchResults[SparqlLink]]

  /**
    * List outgoing links for a given resource.
    *
    * @param id
    *   the resource identifier
    * @param projectRef
    *   the project of the resource
    * @param pagination
    *   the pagination config
    * @param includeExternalLinks
    *   whether to include links to resources not managed by Delta
    */
  def outgoing(
      id: IdSegment,
      projectRef: ProjectRef,
      pagination: FromPagination,
      includeExternalLinks: Boolean
  )(implicit caller: Caller, base: BaseUri): IO[BlazegraphViewRejection, SearchResults[SparqlLink]]

  /**
    * Queries the blazegraph namespace (or namespaces) managed by the view with the passed ''id''. We check for the
    * caller to have the necessary query permissions on the view before performing the query.
    *
    * @param id
    *   the id of the view either in Iri or aliased form
    * @param project
    *   the project where the view exists
    * @param query
    *   the sparql query to run
    * @param responseType
    *   the desired response type
    */
  def query[R <: SparqlQueryResponse](
      id: IdSegment,
      project: ProjectRef,
      query: SparqlQuery,
      responseType: SparqlQueryResponseType.Aux[R]
  )(implicit caller: Caller): IO[BlazegraphViewRejection, R]
}

object BlazegraphViewsQuery {
  private[blazegraph] type FetchView = (IdSegmentRef, ProjectRef) => IO[BlazegraphViewRejection, ViewResource]

  final def apply(
      aclCheck: AclCheck,
      views: BlazegraphViews,
      fetchContext: FetchContext[BlazegraphViewRejection],
      client: SparqlQueryClient
  )(implicit
      config: ExternalIndexingConfig
  ): BlazegraphViewsQuery =
    apply(
      views.fetch,
      BlazegraphViewRefVisitor(views, config),
      fetchContext,
      aclCheck,
      client
    )

  private[blazegraph] def apply(
      fetchView: FetchView,
      visitor: ViewRefVisitor[BlazegraphViewRejection],
      fetchContext: FetchContext[BlazegraphViewRejection],
      aclCheck: AclCheck,
      client: SparqlQueryClient
  )(implicit config: ExternalIndexingConfig): BlazegraphViewsQuery =
    new BlazegraphViewsQuery {

      private val expandIri: ExpandIri[BlazegraphViewRejection] = new ExpandIri(InvalidResourceId.apply)
      implicit private val logger: Logger                       = Logger[BlazegraphViewsQuery]

      implicit private val cl: ClassLoader  = this.getClass.getClassLoader
      private val incomingQuery             =
        ioContentOf("blazegraph/incoming.txt")
          .logAndDiscardErrors("SPARQL 'incoming.txt' template not found")
          .memoizeOnSuccess
      private val outgoingWithExternalQuery =
        ioContentOf("blazegraph/outgoing_include_external.txt")
          .logAndDiscardErrors("SPARQL 'outgoing_include_external.txt' template not found")
          .memoizeOnSuccess
      private val outgoingScopedQuery       =
        ioContentOf("blazegraph/outgoing_scoped.txt")
          .logAndDiscardErrors("SPARQL 'outgoing_scoped.txt' template not found")
          .memoizeOnSuccess

      private def replace(query: String, id: Iri, pagination: FromPagination): String =
        query
          .replaceAll(quote("{id}"), id.toString)
          .replaceAll(quote("{offset}"), pagination.from.toString)
          .replaceAll(quote("{size}"), pagination.size.toString)

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
          p             <- fetchContext.onRead(projectRef)
          iri           <- expandIri(id, p)
          q              = SparqlQuery(replace(queryTemplate, iri, pagination))
          bindings      <- query(IriSegment(defaultViewId), projectRef, q, SparqlResultsJson)
          links          = toSparqlLinks(bindings.value, p.apiMappings, p.base)
        } yield links

      def outgoing(
          id: IdSegment,
          projectRef: ProjectRef,
          pagination: FromPagination,
          includeExternalLinks: Boolean
      )(implicit caller: Caller, base: BaseUri): IO[BlazegraphViewRejection, SearchResults[SparqlLink]] =
        for {
          queryTemplate <- if (includeExternalLinks) outgoingWithExternalQuery else outgoingScopedQuery
          p             <- fetchContext.onRead(projectRef)
          iri           <- expandIri(id, p)
          q              = SparqlQuery(replace(queryTemplate, iri, pagination))
          bindings      <- query(IriSegment(defaultViewId), projectRef, q, SparqlResultsJson)
          links          = toSparqlLinks(bindings.value, p.apiMappings, p.base)
        } yield links

      def query[R <: SparqlQueryResponse](
          id: IdSegment,
          project: ProjectRef,
          query: SparqlQuery,
          responseType: SparqlQueryResponseType.Aux[R]
      )(implicit caller: Caller): IO[BlazegraphViewRejection, R] =
        for {
          view    <- fetchView(id, project)
          _       <- IO.raiseWhen(view.deprecated)(ViewIsDeprecated(view.id))
          indices <- accessibleNamespaces(view)
          qr      <- client.query(indices, query, responseType).mapError(WrappedBlazegraphClientError)
        } yield qr

      private def accessibleNamespaces(
          view: ViewResource
      )(implicit caller: Caller): IO[BlazegraphViewRejection, Set[String]] =
        view.value match {

          case v: IndexingBlazegraphView =>
            aclCheck
              .authorizeForOr(v.project, v.permission)(AuthorizationFailed)
              .as(Set(BlazegraphViews.namespace(view.as(v), config)))

          case v: AggregateBlazegraphView =>
            visitor.visitAll(v.views).map(_.collect { case v: IndexedVisitedView => v }).flatMap { views =>
              aclCheck.mapFilter[IndexedVisitedView, String](
                views,
                v => ProjectAcl(v.ref.project) -> v.permission,
                _.index
              )
            }
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
