package ch.epfl.bluebrain.nexus.delta.sdk.error

import akka.http.scaladsl.model.StatusCodes
import ch.epfl.bluebrain.nexus.delta.kernel.jwt.TokenRejection
import ch.epfl.bluebrain.nexus.delta.kernel.jwt.TokenRejection.InvalidAccessToken
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClassUtils
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.BNode
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.HttpResponseFields
import ch.epfl.bluebrain.nexus.delta.sdk.syntax.httpResponseFieldsSyntax
import io.circe.syntax.EncoderOps
import io.circe.{Encoder, JsonObject}

/**
  * Top level error type that represents issues related to authentication and identities
  *
  * @param reason
  *   a human readable message for why the error occurred
  */
sealed abstract class IdentityError(reason: String) extends SDKError {

  override def getMessage: String = reason
}

object IdentityError {

  /**
    * Signals that the provided authentication is not valid.
    */
  final case object AuthenticationFailed extends IdentityError("The supplied authentication is invalid.")

  /**
    * Signals an attempt to consume the service without a valid oauth2 bearer token.
    *
    * @param rejection
    *   the specific reason why the token is invalid
    */
  final case class InvalidToken(rejection: TokenRejection) extends IdentityError(rejection.getMessage)

  implicit val tokenRejectionEncoder: Encoder.AsObject[TokenRejection] =
    Encoder.AsObject.instance { r =>
      val tpe  = ClassUtils.simpleName(r)
      val json = JsonObject.empty.add(keywords.tpe, tpe.asJson).add("reason", r.getMessage.asJson)
      r match {
        case InvalidAccessToken(_, _, error) => json.add("details", error.asJson)
        case _                               => json
      }
    }

  implicit final val tokenRejectionJsonLdEncoder: JsonLdEncoder[TokenRejection] =
    JsonLdEncoder.computeFromCirce(id = BNode.random, ctx = ContextValue(contexts.error))

  implicit val identityErrorEncoder: Encoder.AsObject[IdentityError] =
    Encoder.AsObject.instance[IdentityError] {
      case InvalidToken(r)      =>
        r.asJsonObject
      case AuthenticationFailed =>
        JsonObject(keywords.tpe -> "AuthenticationFailed".asJson, "reason" -> AuthenticationFailed.getMessage.asJson)
    }

  implicit val identityErrorJsonLdEncoder: JsonLdEncoder[IdentityError] =
    JsonLdEncoder.computeFromCirce(ContextValue(contexts.error))

  implicit val responseFieldsTokenRejection: HttpResponseFields[TokenRejection] =
    HttpResponseFields(_ => StatusCodes.Unauthorized)

  implicit val responseFieldsIdentities: HttpResponseFields[IdentityError] =
    HttpResponseFields {
      case IdentityError.AuthenticationFailed    => StatusCodes.Unauthorized
      case IdentityError.InvalidToken(rejection) => rejection.status
    }
}
