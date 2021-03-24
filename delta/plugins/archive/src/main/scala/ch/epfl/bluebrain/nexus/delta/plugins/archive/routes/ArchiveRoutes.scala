package ch.epfl.bluebrain.nexus.delta.plugins.archive.routes

import akka.http.scaladsl.model.MediaTypes
import akka.http.scaladsl.model.StatusCodes.{Created, SeeOther}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{Directive1, Route}
import ch.epfl.bluebrain.nexus.delta.plugins.archive.Archives
import ch.epfl.bluebrain.nexus.delta.plugins.archive.model.permissions
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.circe.CirceUnmarshalling
import ch.epfl.bluebrain.nexus.delta.sdk.directives.{AuthDirectives, DeltaDirectives, FileResponse}
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.utils.HeadersUtils
import ch.epfl.bluebrain.nexus.delta.sdk.{Acls, AkkaSource, Identities, Projects}
import io.circe.Json
import kamon.instrumentation.akka.http.TracingDirectives.operationName
import monix.execution.Scheduler

/**
  * The Archive routes.
  *
  * @param archives   the archive module
  * @param identities the identities module
  * @param acls       the acls module
  * @param projects   the projects module
  */
class ArchiveRoutes(
    archives: Archives,
    identities: Identities,
    acls: Acls,
    projects: Projects
)(implicit baseUri: BaseUri, rcr: RemoteContextResolution, jko: JsonKeyOrdering, sc: Scheduler)
    extends AuthDirectives(identities, acls)
    with CirceUnmarshalling
    with DeltaDirectives {

  private val prefix = baseUri.prefixSegment

  def routes: Route =
    baseUriPrefix(baseUri.prefix) {
      extractCaller { implicit caller =>
        pathPrefix("archives") {
          projectRef(projects).apply { implicit ref =>
            concat(
              // create an archive without an id
              (post & entity(as[Json]) & pathEndOrSingleSlash) { json =>
                operationName(s"$prefix/archives/{org}/{project}") {
                  authorizeFor(AclAddress.Project(ref), permissions.write).apply {
                    metadataResponse { asMetadata =>
                      if (asMetadata) emit(Created, archives.create(ref, json).mapValue(_.metadata))
                      else emitRedirect(SeeOther, archives.create(ref, json).map(_.uris.accessUri))
                    }
                  }
                }
              },
              (idSegment & pathEndOrSingleSlash) { id =>
                operationName(s"$prefix/archives/{org}/{project}/{id}") {
                  concat(
                    // create an archive with an id
                    (put & entity(as[Json]) & pathEndOrSingleSlash) { json =>
                      authorizeFor(AclAddress.Project(ref), permissions.write).apply {
                        metadataResponse { asMetadata =>
                          if (asMetadata) emit(Created, archives.create(id, ref, json).mapValue(_.metadata))
                          else emitRedirect(SeeOther, archives.create(id, ref, json).map(_.uris.accessUri))
                        }
                      }
                    },
                    // fetch or download an archive
                    (get & pathEndOrSingleSlash) {
                      authorizeFor(AclAddress.Project(ref), permissions.read).apply {
                        metadataResponse { asMetadata =>
                          if (asMetadata) emit(archives.fetch(id, ref))
                          else
                            parameter("ignoreNotFound".as[Boolean] ? false) { ignoreNotFound =>
                              emit(archives.download(id, ref, ignoreNotFound).map(sourceToFileResponse))
                            }
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

  private def metadataResponse: Directive1[Boolean] =
    extractRequest.map { req =>
      HeadersUtils.findFirst(req.headers, mediaTypes, exactMatch = true).isDefined
    }
}
