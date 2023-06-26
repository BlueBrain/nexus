package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.routes

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewRejection
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewRejection.ViewIsDeprecated
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.{ElasticSearchViews, ElasticSearchViewsQuery}
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegment
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef}
import ch.epfl.bluebrain.nexus.testkit.CirceLiteral._
import io.circe.syntax._
import io.circe.{Json, JsonObject}
import monix.bio.IO

private[routes] class DummyElasticSearchViewsQuery(views: ElasticSearchViews) extends ElasticSearchViewsQuery {

  private def toJsonObject(value: Map[String, String]) =
    JsonObject.fromMap(value.map { case (k, v) => k -> v.asJson })

  override def query(
      id: IdSegment,
      project: ProjectRef,
      query: JsonObject,
      qp: Uri.Query
  )(implicit caller: Caller): IO[ElasticSearchViewRejection, Json] = {
    for {
      view <- views.fetch(id, project)
      _    <- IO.raiseWhen(view.deprecated)(ViewIsDeprecated(view.id))
    } yield json"""{"id": "$id", "project": "$project"}""" deepMerge toJsonObject(
      qp.toMap
    ).asJson deepMerge query.asJson
  }
}

object DummyElasticSearchViewsQuery {

  val listResponse: JsonObject = jobj"""{"http://localhost/projects": "all"}"""

  def listResponse(schema: IdSegment): JsonObject =
    jobj"""{"http://localhost/projects": "all", "http://localhost/schema": "${schema.asString}"}"""

  def listResponse(org: Label): JsonObject =
    jobj"""{"http://localhost/projects": "all", "http://localhost/org": "${org}"}"""

  def listResponse(org: Label, schema: IdSegment): JsonObject =
    jobj"""{"http://localhost/projects": "all", "http://localhost/org": "${org}", "http://localhost/schema": "${schema.asString}"}"""

  def listResponse(projectRef: ProjectRef): JsonObject =
    jobj"""{"http://localhost/projects": "all", "http://localhost/project": "$projectRef"}"""

  def listResponse(projectRef: ProjectRef, schema: IdSegment): JsonObject =
    jobj"""{"http://localhost/projects": "all", "http://localhost/project": "$projectRef", "http://localhost/schema": "${schema.asString}"}"""
}
