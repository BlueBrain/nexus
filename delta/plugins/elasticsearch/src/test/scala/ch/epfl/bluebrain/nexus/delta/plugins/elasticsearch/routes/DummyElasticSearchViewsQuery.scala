package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.routes

import akka.http.scaladsl.model.Uri
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.PointInTime
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewRejection.ViewIsDeprecated
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.{ElasticSearchViews, ElasticSearchViewsQuery}
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegment
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.testkit.CirceLiteral.*
import io.circe.syntax.*
import io.circe.{Json, JsonObject}

import scala.concurrent.duration.FiniteDuration

private[routes] class DummyElasticSearchViewsQuery(views: ElasticSearchViews) extends ElasticSearchViewsQuery {

  private def toJsonObject(value: Map[String, String]) =
    JsonObject.fromMap(value.map { case (k, v) => k -> v.asJson })

  override def query(
      id: IdSegment,
      project: ProjectRef,
      query: JsonObject,
      qp: Uri.Query
  )(implicit caller: Caller): IO[Json] = {
    for {
      view <- views.fetch(id, project)
      _    <- IO.raiseWhen(view.deprecated)(ViewIsDeprecated(view.id))
    } yield json"""{"id": "$id", "project": "$project"}""" deepMerge toJsonObject(
      qp.toMap
    ).asJson deepMerge query.asJson
  }

  override def mapping(id: IdSegment, project: ProjectRef)(implicit caller: Caller): IO[Json] =
    IO.pure(json"""{"mappings": "mapping"}""")

  override def createPointInTime(id: IdSegment, project: ProjectRef, keepAlive: FiniteDuration)(implicit
      caller: Caller
  ): IO[PointInTime] =
    IO.pure(PointInTime("xxx"))

  override def deletePointInTime(pointInTime: PointInTime)(implicit caller: Caller): IO[Unit] =
    IO.unit
}
