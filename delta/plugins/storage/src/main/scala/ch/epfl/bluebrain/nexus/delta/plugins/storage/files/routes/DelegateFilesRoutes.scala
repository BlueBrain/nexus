package ch.epfl.bluebrain.nexus.delta.plugins.storage.files.routes

import akka.http.scaladsl.model.StatusCodes.{Created, OK}
import akka.http.scaladsl.server._
import cats.effect.IO
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileDelegationRequest.{FileDelegationCreationRequest, FileDelegationUpdateRequest}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileRejection._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.{FileLinkRequest, _}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.routes.FileUriDirectives._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.{FileResource, Files}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StoragesConfig.ShowFileLocation
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.circe.{CirceMarshalling, CirceUnmarshalling}
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaDirectives._
import ch.epfl.bluebrain.nexus.delta.sdk.directives.{AuthDirectives, ResponseToJsonLd}
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.jws.JWSPayloadHelper
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, IdSegment}
import ch.epfl.bluebrain.nexus.delta.sdk.{IndexingAction, IndexingMode}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import io.circe.Json
import io.circe.syntax.EncoderOps

final class DelegateFilesRoutes(
    identities: Identities,
    aclCheck: AclCheck,
    files: Files,
    jwsPayloadHelper: JWSPayloadHelper,
    index: IndexingAction.Execute[File]
)(implicit
    baseUri: BaseUri,
    cr: RemoteContextResolution,
    ordering: JsonKeyOrdering,
    showLocation: ShowFileLocation
) extends AuthDirectives(identities, aclCheck)
    with CirceUnmarshalling
    with CirceMarshalling { self =>

  def routes: Route =
    baseUriPrefix(baseUri.prefix) {
      pathPrefix("delegate" / "files") {
        extractCaller { implicit caller =>
          concat(
            (pathPrefix("generate") & projectRef) { project =>
              concat(
                // Delegate a file creation without id segment
                (pathEndOrSingleSlash & post & storageParam & tagParam & noRev) { case (storageId, tag) =>
                  entity(as[FileDescription]) { desc =>
                    emit(OK, createDelegation(None, project, storageId, desc, tag).attemptNarrow[FileRejection])
                  }
                },
                // Delegate a file creation without id segment
                (idSegment & pathEndOrSingleSlash & put & storageParam & noRev & tagParam) { (id, storageId, tag) =>
                  entity(as[FileDescription]) { desc =>
                    emit(OK, createDelegation(Some(id), project, storageId, desc, tag).attemptNarrow[FileRejection])
                  }
                },
                // Delegate a file creation without id segment
                (idSegment & pathEndOrSingleSlash & put & storageParam & revParam & tagParam) {
                  (id, storageId, rev, tag) =>
                    entity(as[FileDescription]) { desc =>
                      emit(OK, updateDelegation(id, project, rev, storageId, desc, tag).attemptNarrow[FileRejection])
                    }
                }
              )
            },
            (pathPrefix("submit") & put & pathEndOrSingleSlash & entity(as[Json]) & indexingMode) {
              (jwsPayload, mode) =>
                emit(
                  Created,
                  linkDelegatedFile(jwsPayload, mode).attemptNarrow[FileRejection]: ResponseToJsonLd
                )
            }
          )
        }
      }
    }

  private def createDelegation(
      id: Option[IdSegment],
      project: ProjectRef,
      storageId: Option[IdSegment],
      desc: FileDescription,
      tag: Option[UserTag]
  )(implicit c: Caller) =
    files.createDelegate(id, project, desc, storageId, tag).flatMap { request =>
      jwsPayloadHelper.sign(request.asJson)
    }

  private def updateDelegation(
      id: IdSegment,
      project: ProjectRef,
      rev: Int,
      storageId: Option[IdSegment],
      desc: FileDescription,
      tag: Option[UserTag]
  )(implicit c: Caller) =
    files.updateDelegate(id, project, rev, desc, storageId, tag).flatMap { request =>
      jwsPayloadHelper.sign(request.asJson)
    }

  private def linkDelegatedFile(
      jwsPayload: Json,
      mode: IndexingMode
  )(implicit c: Caller): IO[FileResource] =
    jwsPayloadHelper
      .verifyAs[FileDelegationRequest](jwsPayload)
      .flatMap {
        case FileDelegationCreationRequest(project, id, targetLocation, description, tag)    =>
          val linkRequest = FileLinkRequest(targetLocation.path.path, description.mediaType, description.metadata)
          files.linkFile(Some(id), project, Some(targetLocation.storageId), linkRequest, tag)
        case FileDelegationUpdateRequest(project, id, rev, targetLocation, description, tag) =>
          val fileId      = FileId(id, project)
          val linkRequest = FileLinkRequest(targetLocation.path.path, description.mediaType, description.metadata)
          files.updateLinkedFile(fileId, rev, Some(targetLocation.storageId), linkRequest, tag)
      }
      .flatTap { fileResource =>
        index(fileResource.value.project, fileResource, mode)
      }
}
