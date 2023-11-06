package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.routes

import cats.effect.IO
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.kernel.effect.migration._
import ch.epfl.bluebrain.nexus.delta.kernel.search.Pagination
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.SparqlQueryResponseType.Aux
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.{SparqlClientError, SparqlQueryClient, SparqlQueryResponse}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewRejection.{ViewIsDeprecated, ViewNotFound, WrappedBlazegraphClientError}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.{defaultViewId, SparqlLink}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.{BlazegraphViews, BlazegraphViewsQuery}
import ch.epfl.bluebrain.nexus.delta.rdf.query.SparqlQuery
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, IdSegment}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef

private[routes] class BlazegraphViewsQueryDummy(
    projectRef: ProjectRef,
    client: SparqlQueryClient,
    views: BlazegraphViews,
    links: Map[String, SearchResults[SparqlLink]]
) extends BlazegraphViewsQuery {
  override def incoming(
      id: IdSegment,
      project: ProjectRef,
      pagination: Pagination.FromPagination
  )(implicit caller: Caller, base: BaseUri): IO[SearchResults[SparqlLink]] =
    if (project == projectRef) IO.fromOption(links.get(id.asString))(ViewNotFound(defaultViewId, project))
    else IO.raiseError(ViewNotFound(defaultViewId, project))

  override def outgoing(
      id: IdSegment,
      project: ProjectRef,
      pagination: Pagination.FromPagination,
      includeExternalLinks: Boolean
  )(implicit caller: Caller, base: BaseUri): IO[SearchResults[SparqlLink]] =
    if (project == projectRef) IO.fromOption(links.get(id.asString))(ViewNotFound(defaultViewId, project))
    else IO.raiseError(ViewNotFound(defaultViewId, project))

  override def query[R <: SparqlQueryResponse](
      id: IdSegment,
      project: ProjectRef,
      query: SparqlQuery,
      responseType: Aux[R]
  )(implicit caller: Caller): IO[R] =
    for {
      view     <- views.fetch(id, project)
      _        <- IO.raiseWhen(view.deprecated)(ViewIsDeprecated(view.id))
      response <- client.query(Set(id.toString), query, responseType).toCatsIO.adaptError { case e: SparqlClientError =>
                    WrappedBlazegraphClientError(e)
                  }
    } yield response

}
