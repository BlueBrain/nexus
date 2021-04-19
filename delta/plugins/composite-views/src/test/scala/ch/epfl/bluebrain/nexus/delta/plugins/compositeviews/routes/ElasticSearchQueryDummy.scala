package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.routes

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.ElasticSearchQuery
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewRejection
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegment
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SortList
import io.circe.{Json, JsonObject}
import monix.bio.IO

class ElasticSearchQueryDummy(
    projectionQuery: Map[(IdSegment, JsonObject), Json],
    projectionsQuery: Map[JsonObject, Json]
) extends ElasticSearchQuery {

  override def query(
      id: IdSegment,
      projectionId: IdSegment,
      project: ProjectRef,
      query: JsonObject,
      qp: Uri.Query,
      sort: SortList
  )(implicit caller: Caller): IO[CompositeViewRejection, Json] =
    IO.pure(projectionQuery(projectionId -> query))

  override def queryProjections(
      id: IdSegment,
      project: ProjectRef,
      query: JsonObject,
      qp: Uri.Query,
      sort: SortList
  )(implicit caller: Caller): IO[CompositeViewRejection, Json] =
    IO.pure(projectionsQuery(query))

}
