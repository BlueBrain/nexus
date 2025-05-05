package ch.epfl.bluebrain.nexus.delta.plugins.storage.files.routes

import akka.http.scaladsl.model.StatusCodes.Created
import akka.http.scaladsl.server.*
import cats.effect.IO
import cats.syntax.all.*
import ch.epfl.bluebrain.nexus.akka.marshalling.CirceUnmarshalling
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.{File, FileId, FileLinkRequest, FileRejection}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.{FileResource, Files}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StoragesConfig.ShowFileLocation
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.directives.AuthDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaDirectives.*
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.routes.FileUriDirectives.*
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.{IndexingAction, IndexingMode}

class LinkFilesRoutes(identities: Identities, aclCheck: AclCheck, files: Files, index: IndexingAction.Execute[File])(
    implicit
    baseUri: BaseUri,
    cr: RemoteContextResolution,
    ordering: JsonKeyOrdering,
    showLocation: ShowFileLocation
) extends AuthDirectives(identities, aclCheck)
    with CirceUnmarshalling {
  self =>

  private def onCreationDirective =
    noRev & storageParam & tagParam & indexingMode & pathEndOrSingleSlash & entity(as[FileLinkRequest])

  def routes: Route =
    baseUriPrefix(baseUri.prefix) {
      pathPrefix("link" / "files") {
        extractCaller { implicit caller =>
          projectRef { project =>
            implicit class IndexOps(io: IO[FileResource]) {
              def index(m: IndexingMode): IO[FileResource] = io.flatTap(self.index(project, _, m))
            }
            concat(
              // Link a file without an id segment
              (onCreationDirective & post) { (storage, tag, mode, request) =>
                emit(
                  Created,
                  files
                    .linkFile(None, project, storage, request, tag)
                    .index(mode)
                    .attemptNarrow[FileRejection]
                )
              },
              // Link a file with id segment
              (idSegment & onCreationDirective & put) { (id, storage, tag, mode, request) =>
                emit(
                  Created,
                  files
                    .linkFile(Some(id), project, storage, request, tag)
                    .index(mode)
                    .attemptNarrow[FileRejection]
                )
              },
              // Update a linked file
              (put & idSegment & indexingMode & pathEndOrSingleSlash) { (id, mode) =>
                (revParam & storageParam & tagParam) { (rev, storage, tag) =>
                  entity(as[FileLinkRequest]) { request =>
                    val fileId = FileId(id, project)
                    emit(
                      files
                        .updateLinkedFile(fileId, rev, storage, request, tag)
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
