package ch.epfl.bluebrain.nexus.delta.plugins.storage.files.routes

import akka.http.scaladsl.model.StatusCodes.{Created, OK}
import akka.http.scaladsl.server._
import cats.effect.IO
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileRejection._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model._
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
                // Delegate a file without id segment
                (pathEndOrSingleSlash & post & storageParam & tagParam & noRev) { case (storageId, tag) =>
                  entity(as[FileDescription]) { desc =>
                    emit(OK, validateFileDetails(None, project, storageId, desc, tag).attemptNarrow[FileRejection])
                  }
                },
                // Delegate a file without id segment
                (idSegment & pathEndOrSingleSlash & put & storageParam & tagParam & noRev) { (id, storageId, tag) =>
                  entity(as[FileDescription]) { desc =>
                    emit(OK, validateFileDetails(Some(id), project, storageId, desc, tag).attemptNarrow[FileRejection])
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

  private def validateFileDetails(
      id: Option[IdSegment],
      project: ProjectRef,
      storageId: Option[IdSegment],
      desc: FileDescription,
      tag: Option[UserTag]
  )(implicit c: Caller) =
    for {
      delegationRequest <- files.createDelegate(id, project, desc, storageId, tag)
      jwsPayload        <- jwsPayloadHelper.sign(delegationRequest.asJson)
    } yield jwsPayload

  private def linkDelegatedFile(
      jwsPayload: Json,
      mode: IndexingMode
  )(implicit c: Caller): IO[FileResource] =
    for {
      originalPayload   <- jwsPayloadHelper.verify(jwsPayload)
      delegationRequest <- IO.fromEither(originalPayload.as[FileDelegationRequest])
      linkRequest        = FileLinkRequest(
                             delegationRequest.path.path,
                             delegationRequest.description.mediaType,
                             delegationRequest.description.metadata
                           )
      fileResource      <- files.linkFile(
                             Some(delegationRequest.id),
                             delegationRequest.project,
                             Some(delegationRequest.storageId),
                             linkRequest,
                             delegationRequest.tag
                           )
      _                 <- index(delegationRequest.project, fileResource, mode)
    } yield fileResource
}
