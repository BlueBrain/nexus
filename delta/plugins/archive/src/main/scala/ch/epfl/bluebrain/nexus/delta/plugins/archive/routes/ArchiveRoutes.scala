package ch.epfl.bluebrain.nexus.delta.plugins.archive.routes

import akka.http.scaladsl.model.MediaTypes
import akka.http.scaladsl.model.StatusCodes.{Created, SeeOther}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{Directive1, Route}
import ch.epfl.bluebrain.nexus.delta.plugins.archive.Archives
import ch.epfl.bluebrain.nexus.delta.plugins.archive.model.permissions
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.circe.CirceUnmarshalling
import ch.epfl.bluebrain.nexus.delta.sdk.directives.{AuthDirectives, DeltaDirectives, FileResponse}
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.utils.HeadersUtils
import ch.epfl.bluebrain.nexus.delta.sdk.AkkaSource
import ch.epfl.bluebrain.nexus.delta.sdk.projects.Projects
import io.circe.Json
import kamon.instrumentation.akka.http.TracingDirectives.operationName
import monix.execution.Scheduler

/**
  * The Archive routes.
  *
  * @param archives
  *   the archive module
  * @param identities
  *   the identities module
  * @param aclCheck
  *   to check acls
  * @param projects
  *   the projects module
  */
class ArchiveRoutes(
    archives: Archives,
    identities: Identities,
    aclCheck: AclCheck,
    projects: Projects
)(implicit baseUri: BaseUri, rcr: RemoteContextResolution, jko: JsonKeyOrdering, sc: Scheduler)
    extends AuthDirectives(identities, aclCheck)
    with CirceUnmarshalling
    with DeltaDirectives {

  private val prefix = baseUri.prefixSegment

  def routes: Route =
    baseUriPrefix(baseUri.prefix) {
      pathPrefix("archives") {
        extractCaller { implicit caller =>
          projectRef(projects).apply { implicit ref =>
            concat(
              // create an archive without an id
              (post & entity(as[Json]) & pathEndOrSingleSlash) { json =>
                operationName(s"$prefix/archives/{org}/{project}") {
                  authorizeFor(ref, permissions.write).apply {
                    tarResponse { asTar =>
                      if (asTar) emitRedirect(SeeOther, archives.create(ref, json).map(_.uris.accessUri))
                      else emit(Created, archives.create(ref, json).mapValue(_.metadata))
                    }
                  }
                }
              },
              (idSegment & pathEndOrSingleSlash) { id =>
                operationName(s"$prefix/archives/{org}/{project}/{id}") {
                  concat(
                    // create an archive with an id
                    (put & entity(as[Json]) & pathEndOrSingleSlash) { json =>
                      authorizeFor(ref, permissions.write).apply {
                        tarResponse { asTar =>
                          if (asTar) emitRedirect(SeeOther, archives.create(id, ref, json).map(_.uris.accessUri))
                          else emit(Created, archives.create(id, ref, json).mapValue(_.metadata))
                        }
                      }
                    },
                    // fetch or download an archive
                    (get & pathEndOrSingleSlash) {
                      authorizeFor(ref, permissions.read).apply {
                        tarResponse { asTar =>
                          if (asTar) parameter("ignoreNotFound".as[Boolean] ? false) { ignoreNotFound =>
                            emit(archives.download(id, ref, ignoreNotFound).map(sourceToFileResponse))
                          }
                          else emit(archives.fetch(id, ref))
                        }
                      }
                    }
                  )
                }
              }
            )
          }
        }
      }
    }

  private def sourceToFileResponse(source: AkkaSource): FileResponse =
    FileResponse("archive.tar", MediaTypes.`application/x-tar`, 0L, source)

  private def tarResponse: Directive1[Boolean] =
    extractRequest.map { req =>
      HeadersUtils.matches(req.headers, MediaTypes.`application/x-tar`)
    }
}
