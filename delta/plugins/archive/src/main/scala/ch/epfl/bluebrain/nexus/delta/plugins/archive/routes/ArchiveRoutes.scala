package ch.epfl.bluebrain.nexus.delta.plugins.archive.routes

import akka.http.scaladsl.model.StatusCodes.{Created, SeeOther}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{Directive1, Route}
import ch.epfl.bluebrain.nexus.delta.plugins.archive.Archives
import ch.epfl.bluebrain.nexus.delta.plugins.archive.model.{permissions, ArchiveFormat}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.AkkaSource
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.circe.CirceUnmarshalling
import ch.epfl.bluebrain.nexus.delta.sdk.directives.{AuthDirectives, DeltaDirectives, DeltaSchemeDirectives, FileResponse}
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
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
  * @param schemeDirectives
  *   directives related to orgs and projects
  */
class ArchiveRoutes(
    archives: Archives,
    identities: Identities,
    aclCheck: AclCheck,
    schemeDirectives: DeltaSchemeDirectives
)(implicit baseUri: BaseUri, rcr: RemoteContextResolution, jko: JsonKeyOrdering, sc: Scheduler)
    extends AuthDirectives(identities, aclCheck)
    with CirceUnmarshalling
    with DeltaDirectives {

  private val prefix = baseUri.prefixSegment
  import schemeDirectives._

  def routes: Route =
    baseUriPrefix(baseUri.prefix) {
      pathPrefix("archives") {
        extractCaller { implicit caller =>
          resolveProjectRef.apply { implicit ref =>
            concat(
              // create an archive without an id
              (post & entity(as[Json]) & pathEndOrSingleSlash) { json =>
                operationName(s"$prefix/archives/{org}/{project}") {
                  authorizeFor(ref, permissions.write).apply {
                    archiveResponse {
                      case Some(_) => emitRedirect(SeeOther, archives.create(ref, json).map(_.uris.accessUri))
                      case None    => emit(Created, archives.create(ref, json).mapValue(_.metadata))
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
                        archiveResponse {
                          case Some(_) => emitRedirect(SeeOther, archives.create(id, ref, json).map(_.uris.accessUri))
                          case None    => emit(Created, archives.create(id, ref, json).mapValue(_.metadata))
                        }
                      }
                    },
                    // fetch or download an archive
                    (get & pathEndOrSingleSlash) {
                      authorizeFor(ref, permissions.read).apply {
                        archiveResponse {
                          case Some(format) =>
                            parameter("ignoreNotFound".as[Boolean] ? false) { ignoreNotFound =>
                              val response = archives.download(id, ref, format, ignoreNotFound).map { source =>
                                sourceToFileResponse(source, format)
                              }
                              emit(response)
                            }
                          case None         => emit(archives.fetch(id, ref))
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

  private def sourceToFileResponse(source: AkkaSource, format: ArchiveFormat[_]): FileResponse =
    FileResponse(s"archive.${format.fileExtension}", format.contentType, 0L, source)

  private def archiveResponse: Directive1[Option[ArchiveFormat[_]]] =
    extractRequest.map(ArchiveFormat(_))
}
