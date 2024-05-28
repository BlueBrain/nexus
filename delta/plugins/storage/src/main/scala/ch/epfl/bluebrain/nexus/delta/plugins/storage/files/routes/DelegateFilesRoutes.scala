package ch.epfl.bluebrain.nexus.delta.plugins.storage.files.routes

import akka.http.scaladsl.model.StatusCodes.OK
import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.server._
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.routes.DelegateFilesRoutes._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.Files
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.circe.{CirceMarshalling, CirceUnmarshalling}
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaDirectives._
import ch.epfl.bluebrain.nexus.delta.sdk.directives.AuthDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, IdSegment}
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredDecoder
import io.circe.generic.semiauto.deriveEncoder
import io.circe.syntax.EncoderOps
import io.circe.{Decoder, Encoder}
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._

final class DelegateFilesRoutes(
    identities: Identities,
    aclCheck: AclCheck,
    files: Files,
    tokenIssuer: TokenIssuer
)(implicit
    baseUri: BaseUri
) extends AuthDirectives(identities, aclCheck)
    with CirceUnmarshalling
    with CirceMarshalling { self =>

  private val logger = Logger[DelegateFilesRoutes]

  def routes: Route =
    baseUriPrefix(baseUri.prefix) {
      pathPrefix("delegate" / "files") {
        extractCaller { implicit caller =>
          projectRef { project =>
            (pathEndOrSingleSlash & post) {
              parameter("storage".as[IdSegment].?) { storageId =>
                entity(as[FileDescription]) { desc =>
                  emitWithAuthHeader(
                    files.delegate(project, desc, storageId).attemptNarrow[FileRejection]
                  )
                }
              }
            }
          }
        }
      }
    }

  private def emitWithAuthHeader(resp: IO[Either[FileRejection, DelegationResponse]]): Route = {
    val ioFinal = resp.flatMap {
      case Left(value)  =>
        complete(
          FileRejection.fileRejectionHttpResponseFields.statusFrom(value),
          FileRejection.fileRejectionEncoder.apply(value)
        ).pure[IO]
      case Right(value) =>
        for {
          _         <- logger.info(s"Generated S3 delegation details: $value")
          signature <- tokenIssuer.issueJWSToken(value.asJson)
          authHeader = RawHeader("X-Delta-Delegation-Signature", signature)
          _         <- logger.info(s"Returning auth header with payload signature: $authHeader")
          route     <- IO(complete(OK, headers = Seq(authHeader), value))
        } yield route
    }
    onSuccess(ioFinal.unsafeToFuture())(identity)
  }
}

object DelegateFilesRoutes {

  final case class DelegationResponse(bucket: String, id: Iri, path: Uri)

  object DelegationResponse {
    implicit val enc: Encoder[DelegationResponse] = deriveEncoder
  }

  implicit private val config: Configuration = Configuration.default
  implicit val dec: Decoder[FileDescription] = deriveConfiguredDecoder[FileDescription]
}
