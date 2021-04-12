package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.routes

import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.{SparqlQuery, SparqlResults}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.BlazegraphQuery
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewRejection
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegment
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import monix.bio.IO

class BlazegraphQueryDummy(
    commonNamespace: Map[SparqlQuery, SparqlResults],
    projectionQuery: Map[(IdSegment, SparqlQuery), SparqlResults],
    projectionsQuery: Map[SparqlQuery, SparqlResults]
) extends BlazegraphQuery {
  override def query(
      id: IdSegment,
      project: ProjectRef,
      query: SparqlQuery
  )(implicit caller: Caller): IO[CompositeViewRejection, SparqlResults] =
    IO.pure(commonNamespace(query))

  override def query(
      id: IdSegment,
      projectionId: IdSegment,
      project: ProjectRef,
      query: SparqlQuery
  )(implicit caller: Caller): IO[CompositeViewRejection, SparqlResults] =
    IO.pure(projectionQuery(projectionId -> query))

  override def queryProjections(
      id: IdSegment,
      project: ProjectRef,
      query: SparqlQuery
  )(implicit caller: Caller): IO[CompositeViewRejection, SparqlResults] =
    IO.pure(projectionsQuery(query))

}
