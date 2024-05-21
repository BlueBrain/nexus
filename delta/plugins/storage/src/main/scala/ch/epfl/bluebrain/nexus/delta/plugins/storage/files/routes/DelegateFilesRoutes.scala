package ch.epfl.bluebrain.nexus.delta.plugins.storage.files.routes

import akka.http.scaladsl.model.StatusCodes.OK
import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.model.headers.{Authorization, OAuth2BearerToken}
import akka.http.scaladsl.server._
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.routes.DelegateFilesRoutes._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.{schemas, Files}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.circe.{CirceMarshalling, CirceUnmarshalling}
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaDirectives._
import ch.epfl.bluebrain.nexus.delta.sdk.directives.{AuthDirectives, DeltaSchemeDirectives}
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.HttpResponseFields
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredDecoder
import io.circe.generic.semiauto.deriveEncoder
import io.circe.{Decoder, Encoder}

final class DelegateFilesRoutes(
    identities: Identities,
    aclCheck: AclCheck,
    files: Files,
    schemeDirectives: DeltaSchemeDirectives
)(implicit
    baseUri: BaseUri
) extends AuthDirectives(identities, aclCheck)
    with CirceUnmarshalling
    with CirceMarshalling { self =>

  import schemeDirectives._

  def routes: Route =
    (baseUriPrefix(baseUri.prefix) & replaceUri("files", schemas.files)) {
      pathPrefix("delegate" / "files") {
        extractCaller { implicit caller =>
          projectRef { project =>
            (pathEndOrSingleSlash & post) {
              entity(as[FileDescription]) { desc =>
                emitWithAuthHeader(
                  files.delegate(project, desc).attemptNarrow[FileRejection]
                )
              }
            }
          }
        }
      }
    }

  private def emitWithAuthHeader(resp: IO[Either[FileRejection, DelegationResponse]]): Route = {
    val ioFinal = resp.map {
      case Left(value)  =>
        complete(
          implicitly[HttpResponseFields[FileRejection]].statusFrom(value),
          FileRejection.fileRejectionEncoder.apply(value)
        )
      case Right(value) =>
        val sig = value.signature()
        complete(OK, headers = Seq(Authorization(OAuth2BearerToken(sig))), value)
    }
    onSuccess(ioFinal.unsafeToFuture())(identity)
  }
}

object DelegateFilesRoutes {

  final case class DelegationResponse(bucket: String, id: Iri, path: Uri) {
    def signature(): String = ???
  }

  object DelegationResponse {
    implicit val enc: Encoder[DelegationResponse] = {
      import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
      deriveEncoder
    }
  }

  implicit private val config: Configuration = Configuration.default
  implicit val dec: Decoder[FileDescription] = {
    import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
    deriveConfiguredDecoder[FileDescription]
  }
}
