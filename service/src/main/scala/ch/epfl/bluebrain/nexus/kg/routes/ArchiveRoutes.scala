package ch.epfl.bluebrain.nexus.kg.routes

import akka.http.scaladsl.model.StatusCodes.{Created, OK}
import akka.http.scaladsl.model.headers.Accept
import akka.http.scaladsl.model.{HttpEntity, MediaTypes}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import ch.epfl.bluebrain.nexus.admin.projects.ProjectResource
import ch.epfl.bluebrain.nexus.iam.acls.Acls
import ch.epfl.bluebrain.nexus.iam.realms.Realms
import ch.epfl.bluebrain.nexus.iam.types.Caller
import ch.epfl.bluebrain.nexus.iam.types.Identity.Subject
import ch.epfl.bluebrain.nexus.kg.KgError.{InvalidOutputFormat, UnacceptedResponseContentType}
import ch.epfl.bluebrain.nexus.kg.archives.Archive._
import ch.epfl.bluebrain.nexus.kg.directives.PathDirectives._
import ch.epfl.bluebrain.nexus.kg.directives.ProjectDirectives._
import ch.epfl.bluebrain.nexus.kg.directives.QueryDirectives.outputFormat
import ch.epfl.bluebrain.nexus.kg.marshallers.instances._
import ch.epfl.bluebrain.nexus.kg.resources.ProjectIdentifier.ProjectRef
import ch.epfl.bluebrain.nexus.kg.resources._
import ch.epfl.bluebrain.nexus.kg.routes.OutputFormat.Tar
import ch.epfl.bluebrain.nexus.rdf.Iri.AbsoluteIri
import ch.epfl.bluebrain.nexus.service.config.ServiceConfig
import ch.epfl.bluebrain.nexus.service.directives.AuthDirectives
import io.circe.Json
import kamon.instrumentation.akka.http.TracingDirectives.operationName
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global

class ArchiveRoutes private[routes] (archives: Archives[Task], acls: Acls[Task], realms: Realms[Task])(implicit
    project: ProjectResource,
    caller: Caller,
    config: ServiceConfig
) extends AuthDirectives(acls, realms) {

  private val responseType              = MediaTypes.`application/x-tar`
  private val projectPath               = project.value.path
  implicit private val subject: Subject = caller.subject

  /**
    * Routes for archives. Those routes should get triggered after the following segment have been consumed:
    * {prefix}/archives/{org}/{project}. E.g.: v1/archives/myorg/myproject
    */
  def routes: Route =
    concat(
      // Create an archive when id is not provided in the Uri (POST)
      (post & pathEndOrSingleSlash) {
        operationName(s"/${config.http.prefix}/archives/{org}/{project}") {
          (authorizeFor(projectPath, write) & projectNotDeprecated) {
            entity(as[Json]) { source =>
              val created = archives.create(source)
              outputFormat(strict = true, Tar) {
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
    val resId = Id(ProjectRef(project.uuid), id)
    concat(
      // Create archive
      (put & pathEndOrSingleSlash) {
        operationName(s"/${config.http.prefix}/archives/{org}/{project}/{id}") {
          (authorizeFor(projectPath, write) & projectNotDeprecated) {
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
    (parameter("ignoreNotFound".as[Boolean] ? false) & extractCallerAcls(anyProject)) { (ignoreNotFound, acls) =>
      onSuccess(archives.fetchArchive(resId, ignoreNotFound)(acls, caller).value.runToFuture) {
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
