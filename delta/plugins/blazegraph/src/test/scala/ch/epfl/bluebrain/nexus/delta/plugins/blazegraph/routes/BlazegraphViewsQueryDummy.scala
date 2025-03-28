package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.routes

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.SparqlQueryResponseType.Aux
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.{SparqlClientError, SparqlQueryClient, SparqlQueryResponse}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewRejection.{ViewIsDeprecated, WrappedBlazegraphClientError}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.{BlazegraphViews, BlazegraphViewsQuery}
import ch.epfl.bluebrain.nexus.delta.rdf.query.SparqlQuery
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegment
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef

private[routes] class BlazegraphViewsQueryDummy(
    client: SparqlQueryClient,
    views: BlazegraphViews
) extends BlazegraphViewsQuery {

  override def query[R <: SparqlQueryResponse](
      id: IdSegment,
      project: ProjectRef,
      query: SparqlQuery,
      responseType: Aux[R]
  )(implicit caller: Caller): IO[R] =
    for {
      view     <- views.fetch(id, project)
      _        <- IO.raiseWhen(view.deprecated)(ViewIsDeprecated(view.id))
      response <- client.query(Set(id.toString), query, responseType).adaptError { case e: SparqlClientError =>
                    WrappedBlazegraphClientError(e)
                  }
    } yield response

}
