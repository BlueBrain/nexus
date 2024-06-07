package ch.epfl.bluebrain.nexus.delta.plugins.storage.files.routes

import akka.http.scaladsl.model.StatusCodes.{Created, OK}
import akka.http.scaladsl.model.{ContentType, Uri}
import akka.http.scaladsl.server._
import cats.effect.IO
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileRejection._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.routes.DelegateFilesRoutes._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.{FileResource, Files}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StoragesConfig.ShowFileLocation
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.{IndexingAction, IndexingMode}
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.circe.{CirceMarshalling, CirceUnmarshalling}
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaDirectives._
import ch.epfl.bluebrain.nexus.delta.sdk.directives.{AuthDirectives, DeltaSchemeDirectives, ResponseToJsonLd}
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, IdSegment}
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredDecoder
import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
import io.circe.syntax.EncoderOps
import io.circe.{Decoder, Encoder, Json}
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef

final class DelegateFilesRoutes(
    identities: Identities,
    aclCheck: AclCheck,
    files: Files,
    tokenIssuer: TokenIssuer,
    index: IndexingAction.Execute[File],
    schemeDirectives: DeltaSchemeDirectives
)(implicit
    baseUri: BaseUri,
    cr: RemoteContextResolution,
    ordering: JsonKeyOrdering,
    showLocation: ShowFileLocation
) extends AuthDirectives(identities, aclCheck)
    with CirceUnmarshalling
    with CirceMarshalling { self =>

  import schemeDirectives._

  def routes: Route =
    baseUriPrefix(baseUri.prefix) {
      pathPrefix("delegate" / "files") {
        extractCaller { implicit caller =>
          projectRef { project =>
            concat(
              pathPrefix("validate") {
                (pathEndOrSingleSlash & post) {
                  parameter("storage".as[IdSegment].?) { storageId =>
                    entity(as[FileDescription]) { desc =>
                      emit(OK, validateFileDetails(project, storageId, desc).attemptNarrow[FileRejection])
                    }
                  }
                }
              },
              (pathEndOrSingleSlash & post) {
                (parameter("storage".as[IdSegment].?) & indexingMode) { (storageId, mode) =>
                  entity(as[Json]) { jwsPayload =>
                    emit(
                      Created,
                      registerDelegatedFile(jwsPayload, project, storageId, mode)
                        .attemptNarrow[FileRejection]: ResponseToJsonLd
                    )
                  }
                }
              }
            )
          }
        }
      }
    }

  private def validateFileDetails(project: ProjectRef, storageId: Option[IdSegment], desc: FileDescription)(implicit
      c: Caller
  ) =
    for {
      delegationResp <- files.delegate(project, desc, storageId)
      jwsPayload     <- tokenIssuer.issueJWSPayload(delegationResp.asJson)
    } yield jwsPayload

  private def registerDelegatedFile(
      jwsPayload: Json,
      project: ProjectRef,
      storageId: Option[IdSegment],
      mode: IndexingMode
  )(implicit c: Caller): IO[FileResource] =
    for {
      originalPayload    <- tokenIssuer.verifyJWSPayload(jwsPayload)
      delegationResponse <- IO.fromEither(originalPayload.as[DelegationResponse])
      fileId              = FileId(delegationResponse.id, project)
      fileResource       <-
        files.registerFile(
          fileId,
          storageId,
          delegationResponse.metadata,
          delegationResponse.path.path,
          None,
          delegationResponse.mediaType
        )
      _                  <- index(project, fileResource, mode)
    } yield fileResource

}

object DelegateFilesRoutes {

  final case class DelegationResponse(bucket: String, id: Iri, path: Uri, metadata: Option[FileCustomMetadata], mediaType: Option[ContentType])

  object DelegationResponse {
    implicit val enc: Encoder[DelegationResponse] = deriveEncoder
    implicit val dec: Decoder[DelegationResponse] = deriveDecoder
  }

  implicit private val config: Configuration = Configuration.default
  implicit val dec: Decoder[FileDescription] = deriveConfiguredDecoder[FileDescription]
}
