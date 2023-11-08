package ch.epfl.bluebrain.nexus.delta.plugins.archive.routes

import akka.http.scaladsl.model.StatusCode
import akka.http.scaladsl.model.StatusCodes.{Created, SeeOther}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import cats.effect.IO
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.plugins.archive.Archives
import ch.epfl.bluebrain.nexus.delta.plugins.archive.model.{permissions, ArchiveRejection, ArchiveResource, Zip}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.AkkaSource
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaDirectives._
import ch.epfl.bluebrain.nexus.delta.sdk.circe.CirceUnmarshalling
import ch.epfl.bluebrain.nexus.delta.sdk.directives.{AuthDirectives, DeltaSchemeDirectives, FileResponse}
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, IdSegment}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import io.circe.Json

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
)(implicit baseUri: BaseUri, rcr: RemoteContextResolution, jko: JsonKeyOrdering)
    extends AuthDirectives(identities, aclCheck)
    with CirceUnmarshalling {

  import schemeDirectives._

  def routes: Route =
    baseUriPrefix(baseUri.prefix) {
      pathPrefix("archives") {
        extractCaller { implicit caller =>
          resolveProjectRef.apply { implicit project =>
            concat(
              // create an archive without an id
              (post & entity(as[Json]) & pathEndOrSingleSlash) { json =>
                authorizeFor(project, permissions.write).apply {
                  emitCreatedArchive(archives.create(project, json))
                }
              },
              (idSegment & pathEndOrSingleSlash) { id =>
                concat(
                  // create an archive with an id
                  (put & entity(as[Json]) & pathEndOrSingleSlash) { json =>
                    authorizeFor(project, permissions.write).apply {
                      emitCreatedArchive(archives.create(id, project, json))
                    }
                  },
                  // fetch or download an archive
                  (get & pathEndOrSingleSlash) {
                    authorizeFor(project, permissions.read).apply {
                      emitArchiveDownload(id, project)
                    }
                  }
                )
              }
            )
          }
        }
      }
    }

  private def emitMetadata(statusCode: StatusCode, io: IO[ArchiveResource]): Route =
    emit(statusCode, io.mapValue(_.metadata).attemptNarrow[ArchiveRejection])

  private def emitArchiveFile(source: IO[AkkaSource]) = {
    val response = source.map { s =>
      FileResponse(s"archive.zip", Zip.contentType, 0L, s)
    }
    emit(response.attemptNarrow[ArchiveRejection])
  }

  private def emitCreatedArchive(io: IO[ArchiveResource]): Route =
    Zip.checkHeader {
      case true  => emitRedirect(SeeOther, io.map(_.uris.accessUri).attemptNarrow[ArchiveRejection])
      case false => emitMetadata(Created, io)
    }

  private def emitArchiveDownload(id: IdSegment, project: ProjectRef)(implicit caller: Caller): Route =
    Zip.checkHeader {
      case true  =>
        parameter("ignoreNotFound".as[Boolean] ? false) { ignoreNotFound =>
          emitArchiveFile(archives.download(id, project, ignoreNotFound))
        }
      case false => emit(archives.fetch(id, project).attemptNarrow[ArchiveRejection])
    }
}
