package ch.epfl.bluebrain.nexus.delta.plugins.storage.files.routes

import akka.http.scaladsl.model.StatusCodes.Created
import akka.http.scaladsl.server._
import cats.effect.IO
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.{File, FileId, FileLinkRequest, FileRejection}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.{FileResource, Files}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StoragesConfig.ShowFileLocation
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.circe.CirceUnmarshalling
import ch.epfl.bluebrain.nexus.delta.sdk.directives.AuthDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaDirectives._
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, IdSegment}
import ch.epfl.bluebrain.nexus.delta.sdk.{IndexingAction, IndexingMode}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag

class LinkFilesRoutes(identities: Identities, aclCheck: AclCheck, files: Files, index: IndexingAction.Execute[File])(
    implicit
    baseUri: BaseUri,
    cr: RemoteContextResolution,
    ordering: JsonKeyOrdering,
    showLocation: ShowFileLocation
) extends AuthDirectives(identities, aclCheck)
    with CirceUnmarshalling {
  self =>

  def routes: Route =
    baseUriPrefix(baseUri.prefix) {
      pathPrefix("link" / "files") {
        extractCaller { implicit caller =>
          projectRef { project =>
            implicit class IndexOps(io: IO[FileResource]) {
              def index(m: IndexingMode): IO[FileResource] = io.flatTap(self.index(project, _, m))
            }
            concat(
              // Link a file with id segment
              (put & idSegment & indexingMode & pathEndOrSingleSlash) { (id, mode) =>
                (noParameter("rev") & parameters("storage".as[IdSegment].?, "tag".as[UserTag].?)) { (storage, tag) =>
                  entity(as[FileLinkRequest]) { request =>
                    val fileId = FileId(id, project)
                    emit(
                      Created,
                      files
                        .linkFile(fileId, storage, request, tag)
                        .index(mode)
                        .attemptNarrow[FileRejection]
                    )
                  }
                }
              },
              // Update a linked file
              (put & idSegment & indexingMode & pathEndOrSingleSlash) { (id, mode) =>
                parameters("rev".as[Int], "storage".as[IdSegment].?, "tag".as[UserTag].?) { (rev, storage, tag) =>
                  entity(as[FileLinkRequest]) { request =>
                    val fileId = FileId(id, project)
                    emit(
                      files
                        .updateLinkedFile(fileId, storage, rev, request, tag)
                        .index(mode)
                        .attemptNarrow[FileRejection]
                    )
                  }
                }
              }
            )
          }
        }

      }
    }
}
