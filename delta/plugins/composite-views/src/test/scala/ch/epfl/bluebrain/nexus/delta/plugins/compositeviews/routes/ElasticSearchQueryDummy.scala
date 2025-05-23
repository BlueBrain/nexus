package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.routes

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewRejection.ViewIsDeprecated
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.{CompositeViews, ElasticSearchQuery}
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegment
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import io.circe.{Json, JsonObject}
import org.http4s.Query

class ElasticSearchQueryDummy(
    projectionQuery: Map[(IdSegment, JsonObject), Json],
    projectionsQuery: Map[JsonObject, Json],
    views: CompositeViews
) extends ElasticSearchQuery {

  override def query(
      id: IdSegment,
      projectionId: IdSegment,
      project: ProjectRef,
      query: JsonObject,
      qp: Query
  )(implicit caller: Caller): IO[Json] =
    for {
      view <- views.fetch(id, project)
      _    <- IO.raiseWhen(view.deprecated)(ViewIsDeprecated(view.id))
    } yield projectionQuery(projectionId -> query)

  override def queryProjections(
      id: IdSegment,
      project: ProjectRef,
      query: JsonObject,
      qp: Query
  )(implicit caller: Caller): IO[Json] =
    for {
      view <- views.fetch(id, project)
      _    <- IO.raiseWhen(view.deprecated)(ViewIsDeprecated(view.id))
    } yield projectionsQuery(query)

}
