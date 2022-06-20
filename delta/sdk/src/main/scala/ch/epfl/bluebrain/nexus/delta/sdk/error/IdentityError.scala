package ch.epfl.bluebrain.nexus.delta.sdk.error

import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.TokenRejection
import io.circe.syntax._
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
  final case class InvalidToken(rejection: TokenRejection) extends IdentityError(rejection.reason)

  implicit val identityErrorEncoder: Encoder.AsObject[IdentityError] =
    Encoder.AsObject.instance[IdentityError] {
      case InvalidToken(r)      =>
        r.asJsonObject
      case AuthenticationFailed =>
        JsonObject(keywords.tpe -> "AuthenticationFailed".asJson, "reason" -> AuthenticationFailed.getMessage.asJson)
    }

  implicit val identityErrorJsonLdEncoder: JsonLdEncoder[IdentityError] =
    JsonLdEncoder.computeFromCirce(ContextValue(contexts.error))
}
