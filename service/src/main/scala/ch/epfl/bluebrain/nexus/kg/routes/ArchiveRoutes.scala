package ch.epfl.bluebrain.nexus.kg.routes

import akka.http.scaladsl.model.StatusCodes.{Created, OK}
import akka.http.scaladsl.model.headers.Accept
import akka.http.scaladsl.model.{HttpEntity, MediaTypes}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import ch.epfl.bluebrain.nexus.admin.client.types.Project
import ch.epfl.bluebrain.nexus.iam.client.types._
import ch.epfl.bluebrain.nexus.kg.KgError.{InvalidOutputFormat, UnacceptedResponseContentType}
import ch.epfl.bluebrain.nexus.kg.archives.Archive._
import ch.epfl.bluebrain.nexus.kg.config.AppConfig
import ch.epfl.bluebrain.nexus.kg.directives.AuthDirectives.hasPermission
import ch.epfl.bluebrain.nexus.kg.directives.PathDirectives._
import ch.epfl.bluebrain.nexus.kg.directives.ProjectDirectives._
import ch.epfl.bluebrain.nexus.kg.directives.QueryDirectives.outputFormat
import ch.epfl.bluebrain.nexus.kg.marshallers.instances._
import ch.epfl.bluebrain.nexus.kg.resources._
import ch.epfl.bluebrain.nexus.kg.resources.syntax._
import ch.epfl.bluebrain.nexus.kg.routes.OutputFormat.Tar
import ch.epfl.bluebrain.nexus.rdf.Iri.AbsoluteIri
import io.circe.Json
import kamon.instrumentation.akka.http.TracingDirectives.operationName
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global

class ArchiveRoutes private[routes] (archives: Archives[Task])(implicit
    acls: AccessControlLists,
    project: Project,
    caller: Caller,
    config: AppConfig
) {

  private val responseType = MediaTypes.`application/x-tar`

  /**
    * Routes for archives. Those routes should get triggered after the following segment have been consumed:
    * {prefix}/archives/{org}/{project}. E.g.: v1/archives/myorg/myproject
    */
  def routes: Route =
    concat(
      // Create an archive when id is not provided in the Uri (POST)
      (post & pathEndOrSingleSlash) {
        operationName(s"/${config.http.prefix}/archives/{org}/{project}") {
          (hasPermission(write) & projectNotDeprecated) {
            entity(as[Json]) { source =>
              val created = archives.create(source)
              (outputFormat(strict = true, Tar)) {
                case _: JsonLDOutputFormat => complete(created.value.runWithStatus(Created))
                case _                     => created.map(ResourceRedirect.apply).value.completeRedirect()
              }
            }
          }
        }
      },
      // Consume the archive id segment
      pathPrefix(IdSegment) { id =>
        routes(id)
      }
    )

  /**
    * Routes for archives when the id is specified.
    * This route should get triggered after the following segment have been consumed:
    * {prefix}/archives/{org}/{project}/{id}. E.g.: v1/archives/myorg/myproject/myArchive
    */
  def routes(id: AbsoluteIri): Route = {
    val resId = Id(project.ref, id)
    concat(
      // Create archive
      (put & pathEndOrSingleSlash) {
        operationName(s"/${config.http.prefix}/archives/{org}/{project}/{id}") {
          (hasPermission(write) & projectNotDeprecated) {
            entity(as[Json]) { source =>
              complete(archives.create(resId, source).value.runWithStatus(Created))
            }
          }
        }
      },
      // Fetch archive
      (get & outputFormat(strict = true, Tar) & pathEndOrSingleSlash) {
        case Tar                           => getArchive(resId)
        case format: NonBinaryOutputFormat => getResource(resId)(format)
        case other                         => failWith(InvalidOutputFormat(other.toString))

      }
    )
  }

  private def getResource(resId: ResId)(implicit format: NonBinaryOutputFormat): Route =
    completeWithFormat(archives.fetch(resId).value.runWithStatus(OK))

  private def getArchive(resId: ResId): Route = {
    parameter("ignoreNotFound".as[Boolean] ? false) { ignoreNotFound =>
      onSuccess(archives.fetchArchive(resId, ignoreNotFound).value.runToFuture) {
        case Right(source) =>
          headerValueByType[Accept](()) { accept =>
            if (accept.mediaRanges.exists(_.matches(responseType)))
              complete(HttpEntity(responseType, source))
            else
              failWith(
                UnacceptedResponseContentType(
                  s"File Media Type '$responseType' does not match the Accept header value '${accept.mediaRanges.mkString(", ")}'"
                )
              )
          }
        case Left(err)     => complete(err)
      }
    }
  }
}

object ArchiveRoutes {
  final def apply(archives: Archives[Task])(implicit
      acls: AccessControlLists,
      caller: Caller,
      project: Project,
      config: AppConfig
  ): ArchiveRoutes = new ArchiveRoutes(archives)
}
