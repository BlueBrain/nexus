package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph

import cats.effect.IO
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.effect.migration._
import ch.epfl.bluebrain.nexus.delta.kernel.search.Pagination.FromPagination
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClasspathResourceUtils.ioContentOf
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.SparqlQueryResponseType.{Aux, SparqlResultsJson}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client._
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewRejection._
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewValue.{AggregateBlazegraphViewValue, IndexingBlazegraphViewValue}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.SparqlLink.{SparqlExternalLink, SparqlResourceLink}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model._
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.slowqueries.BlazegraphSlowQueryLogger
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.query.SparqlQuery
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclAddress.{Project => ProjectAcl}
import ch.epfl.bluebrain.nexus.delta.sdk.error.ServiceError.AuthorizationFailed
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.ExpandIri
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegment.IriSegment
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.ResultEntry.UnscoredResultEntry
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults.UnscoredSearchResults
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, IdSegment}
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContext
import ch.epfl.bluebrain.nexus.delta.sdk.views.View.{AggregateView, IndexingView}
import ch.epfl.bluebrain.nexus.delta.sdk.views.{ViewRef, ViewsStore}
import ch.epfl.bluebrain.nexus.delta.sourcing.Transactors
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef

import java.util.regex.Pattern.quote

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
  )(implicit caller: Caller, base: BaseUri): IO[SearchResults[SparqlLink]]

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
  )(implicit caller: Caller, base: BaseUri): IO[SearchResults[SparqlLink]]

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
  )(implicit caller: Caller): IO[R]
}

object BlazegraphViewsQuery {

  final def apply(
      aclCheck: AclCheck,
      fetchContext: FetchContext[BlazegraphViewRejection],
      views: BlazegraphViews,
      client: SparqlQueryClient,
      logSlowQueries: BlazegraphSlowQueryLogger,
      prefix: String,
      xas: Transactors
  ): IO[BlazegraphViewsQuery] = {
    implicit val cl: ClassLoader = this.getClass.getClassLoader
    for {
      incomingQuery             <- ioContentOf("blazegraph/incoming.txt")
      outgoingWithExternalQuery <- ioContentOf("blazegraph/outgoing_include_external.txt")
      outgoingScopedQuery       <- ioContentOf("blazegraph/outgoing_scoped.txt")
      viewsStore                 = ViewsStore[BlazegraphViewRejection, BlazegraphViewState](
                                     BlazegraphViewState.serializer,
                                     views.fetchState(_, _).toBIO[BlazegraphViewRejection],
                                     view =>
                                       IO.raiseWhen(view.deprecated)(ViewIsDeprecated(view.id))
                                         .as {
                                           view.value match {
                                             case _: AggregateBlazegraphViewValue =>
                                               Left(view.id)
                                             case i: IndexingBlazegraphViewValue  =>
                                               Right(
                                                 IndexingView(
                                                   ViewRef(view.project, view.id),
                                                   BlazegraphViews.namespace(view.uuid, view.indexingRev, prefix),
                                                   i.permission
                                                 )
                                               )
                                           }
                                         }
                                         .toBIO[BlazegraphViewRejection],
                                     xas
                                   )
    } yield new BlazegraphViewsQuery {

      private val expandIri: ExpandIri[BlazegraphViewRejection] = new ExpandIri(InvalidResourceId.apply)

      private def replace(query: String, id: Iri, pagination: FromPagination): String =
        query
          .replaceAll(quote("{id}"), id.toString)
          .replaceAll(quote("{offset}"), pagination.from.toString)
          .replaceAll(quote("{size}"), pagination.size.toString)

      override def incoming(id: IdSegment, projectRef: ProjectRef, pagination: FromPagination)(implicit
          caller: Caller,
          base: BaseUri
      ): IO[SearchResults[SparqlLink]] =
        for {
          p        <- fetchContext.onRead(projectRef)
          iri      <- expandIri(id, p)
          q         = SparqlQuery(replace(incomingQuery, iri, pagination))
          bindings <- query(IriSegment(defaultViewId), projectRef, q, SparqlResultsJson)
          links     = toSparqlLinks(bindings.value)
        } yield links

      override def outgoing(
          id: IdSegment,
          projectRef: ProjectRef,
          pagination: FromPagination,
          includeExternalLinks: Boolean
      )(implicit caller: Caller, base: BaseUri): IO[SearchResults[SparqlLink]] =
        for {
          p            <- fetchContext.onRead(projectRef)
          iri          <- expandIri(id, p)
          queryTemplate = if (includeExternalLinks) outgoingWithExternalQuery else outgoingScopedQuery
          q             = SparqlQuery(replace(queryTemplate, iri, pagination))
          bindings     <- query(IriSegment(defaultViewId), projectRef, q, SparqlResultsJson)
          links         = toSparqlLinks(bindings.value)
        } yield links

      override def query[R <: SparqlQueryResponse](
          id: IdSegment,
          project: ProjectRef,
          query: SparqlQuery,
          responseType: Aux[R]
      )(implicit caller: Caller): IO[R] =
        for {
          view    <- viewsStore.fetch(id, project)
          p       <- fetchContext.onRead(project)
          iri     <- expandIri(id, p)
          indices <- view match {
                       case i: IndexingView  =>
                         aclCheck
                           .authorizeForOr(i.ref.project, i.permission)(
                             AuthorizationFailed(i.ref.project, i.permission)
                           )
                           .as(Set(i.index))
                       case a: AggregateView =>
                         aclCheck
                           .mapFilter[IndexingView, String](
                             a.views,
                             v => ProjectAcl(v.ref.project) -> v.permission,
                             _.index
                           )
                     }
          qr      <- logSlowQueries(
                       BlazegraphQueryContext(ViewRef.apply(project, iri), query, caller.subject),
                       client.query(indices, query, responseType).adaptError { case e: SparqlClientError =>
                         WrappedBlazegraphClientError(e)
                       }
                     )
        } yield qr

      private def toSparqlLinks(sparqlResults: SparqlResults)(implicit
          base: BaseUri
      ): SearchResults[SparqlLink] = {
        val (count, results) =
          sparqlResults.results.bindings
            .foldLeft((0L, List.empty[SparqlLink])) { case ((total, acc), bindings) =>
              val newTotal = bindings.get("total").flatMap(v => v.value.toLongOption).getOrElse(total)
              val res      = (SparqlResourceLink(bindings) orElse SparqlExternalLink(bindings))
                .map(_ :: acc)
                .getOrElse(acc)
              (newTotal, res)
            }
        UnscoredSearchResults(count, results.map(UnscoredResultEntry(_)))
      }
    }
  }

  final case class BlazegraphQueryContext(view: ViewRef, query: SparqlQuery, subject: Subject)
}
