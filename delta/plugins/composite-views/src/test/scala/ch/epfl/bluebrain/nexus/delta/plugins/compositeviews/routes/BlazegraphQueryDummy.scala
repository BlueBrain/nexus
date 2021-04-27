package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.routes

import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.SparqlQueryResponseType.Aux
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.{SparqlQuery, SparqlQueryClient, SparqlQueryResponse}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.BlazegraphQuery
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewRejection
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewRejection.WrappedBlazegraphClientError
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegment
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import monix.bio.IO

class BlazegraphQueryDummy(client: SparqlQueryClient) extends BlazegraphQuery {

  override def query[R <: SparqlQueryResponse](
      id: IdSegment,
      project: ProjectRef,
      query: SparqlQuery,
      responseType: Aux[R]
  )(implicit caller: Caller): IO[CompositeViewRejection, R] =
    client.query(Set("queryCommonNs"), query, responseType).mapError(WrappedBlazegraphClientError)

  override def query[R <: SparqlQueryResponse](
      id: IdSegment,
      projectionId: IdSegment,
      project: ProjectRef,
      query: SparqlQuery,
      responseType: Aux[R]
  )(implicit caller: Caller): IO[CompositeViewRejection, R] =
    client.query(Set("queryProjection"), query, responseType).mapError(WrappedBlazegraphClientError)

  override def queryProjections[R <: SparqlQueryResponse](
      id: IdSegment,
      project: ProjectRef,
      query: SparqlQuery,
      responseType: Aux[R]
  )(implicit caller: Caller): IO[CompositeViewRejection, R] =
    client.query(Set("queryProjections"), query, responseType).mapError(WrappedBlazegraphClientError)

}
