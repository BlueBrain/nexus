package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.routes

import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.SparqlQueryResponseType.Aux
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.{SparqlQuery, SparqlQueryClient, SparqlQueryResponse}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewRejection.{ViewIsDeprecated, ViewNotFound, WrappedBlazegraphClientError}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.{defaultViewId, BlazegraphViewRejection, SparqlLink}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.{BlazegraphViews, BlazegraphViewsQuery}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.{Pagination, SearchResults}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, IdSegment}
import monix.bio.IO

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
  )(implicit caller: Caller, base: BaseUri): IO[BlazegraphViewRejection, SearchResults[SparqlLink]] =
    if (project == projectRef) IO.fromOption(links.get(id.asString), ViewNotFound(defaultViewId, project))
    else IO.raiseError(ViewNotFound(defaultViewId, project))

  override def outgoing(
      id: IdSegment,
      project: ProjectRef,
      pagination: Pagination.FromPagination,
      includeExternalLinks: Boolean
  )(implicit caller: Caller, base: BaseUri): IO[BlazegraphViewRejection, SearchResults[SparqlLink]] =
    if (project == projectRef) IO.fromOption(links.get(id.asString), ViewNotFound(defaultViewId, project))
    else IO.raiseError(ViewNotFound(defaultViewId, project))

  override def query[R <: SparqlQueryResponse](
      id: IdSegment,
      project: ProjectRef,
      query: SparqlQuery,
      responseType: Aux[R]
  )(implicit caller: Caller): IO[BlazegraphViewRejection, R] =
    for {
      view     <- views.fetch(id, project)
      _        <- IO.raiseWhen(view.deprecated)(ViewIsDeprecated(view.id))
      response <- client.query(Set(id.toString), query, responseType).mapError(WrappedBlazegraphClientError)
    } yield response

}
