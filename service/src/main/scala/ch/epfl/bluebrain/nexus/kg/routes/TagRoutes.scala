package ch.epfl.bluebrain.nexus.kg.routes

import akka.http.scaladsl.model.StatusCodes.{Created, OK}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import ch.epfl.bluebrain.nexus.admin.client.types.Project
import ch.epfl.bluebrain.nexus.iam.client.types._
import ch.epfl.bluebrain.nexus.kg.config.AppConfig
import ch.epfl.bluebrain.nexus.kg.config.Contexts.tagCtxUri
import ch.epfl.bluebrain.nexus.kg.config.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.kg.directives.AuthDirectives._
import ch.epfl.bluebrain.nexus.kg.directives.ProjectDirectives._
import ch.epfl.bluebrain.nexus.kg.marshallers.instances._
import ch.epfl.bluebrain.nexus.kg.resources._
import ch.epfl.bluebrain.nexus.kg.resources.syntax._
import ch.epfl.bluebrain.nexus.rdf.Iri.AbsoluteIri
import ch.epfl.bluebrain.nexus.rdf.implicits._
import io.circe.syntax._
import io.circe.{Encoder, Json}
import kamon.Kamon
import kamon.instrumentation.akka.http.TracingDirectives.operationName
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global

class TagRoutes private[routes] (resourceType: String, tags: Tags[Task], schema: Ref, write: Permission)(implicit
    acls: AccessControlLists,
    caller: Caller,
    project: Project,
    config: AppConfig
) {

  /**
    * Routes for tags when the id is specified.
    * Those routes should get triggered after the following segments have been consumed:
    * <ul>
    *   <li> {prefix}/{resourceType}/{org}/{project}/{id}. E.g.: v1/views/myorg/myproject/myview </li>
    *   <li> {prefix}/resources/{org}/{project}/{viewSchemaUri}/{id}. E.g.: v1/resources/myorg/myproject/view/myview </li>
    * </ul>
    */
  def routes(id: AbsoluteIri): Route =
    // Consume the tag segment
    pathPrefix("tags") {
      concat(
        // Create tag
        (post & parameter("rev".as[Long]) & pathEndOrSingleSlash) { rev =>
          operationName(opName) {
            (hasPermission(write) & projectNotDeprecated) {
              entity(as[Json]) { source =>
                Kamon.currentSpan().tag("resource.operation", "create")
                complete(tags.create(Id(project.ref, id), rev, source, schema).value.runWithStatus(Created))
              }
            }
          }
        },
        // Fetch a tag
        (get & projectNotDeprecated & pathEndOrSingleSlash) {
          operationName(opName) {
            hasPermission(read).apply {
              parameter("rev".as[Long].?) {
                case Some(rev) => complete(tags.fetch(Id(project.ref, id), rev, schema).value.runWithStatus(OK))
                case _         => complete(tags.fetch(Id(project.ref, id), schema).value.runWithStatus(OK))
              }
            }
          }
        }
      )
    }

  implicit private def tagsEncoder: Encoder[TagSet] =
    Encoder.instance(tags => Json.obj(nxv.tags.prefix -> Json.arr(tags.map(_.asJson).toSeq: _*)).addContext(tagCtxUri))

  private def opName: String                        =
    resourceType match {
      case "resources" => s"/${config.http.prefix}/resources/{org}/{project}/{schemaId}/{id}/tags"
      case _           => s"/${config.http.prefix}/$resourceType/{org}/{project}/{id}/tags"
    }
}
