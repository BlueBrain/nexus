package ch.epfl.bluebrain.nexus.delta.sdk.error

import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.kernel.http.HttpClientError
import ch.epfl.bluebrain.nexus.delta.sdk.realms.model.Realm
import io.circe.syntax.{EncoderOps, KeyOps}
import io.circe.{DecodingFailure, Encoder, JsonObject}

sealed abstract class AuthTokenError(reason: String) extends SDKError {
  override def getMessage: String = reason
}

object AuthTokenError {

  /**
    * Signals that an HTTP error occurred when fetching the token
    */
  final case class AuthTokenHttpError(cause: HttpClientError)
      extends AuthTokenError(s"HTTP error when requesting auth token: ${cause.reason}")

  /**
    * Signals that the token was missing from the authentication response
    */
  final case class AuthTokenNotFoundInResponse(failure: DecodingFailure)
      extends AuthTokenError(s"Auth token not found in auth response: ${failure.reason}")

  /**
    * Signals that the realm specified for authentication is deprecated
    */
  final case class RealmIsDeprecated(realm: Realm)
      extends AuthTokenError(s"Realm for authentication is deprecated: ${realm.label}")

  implicit val identityErrorEncoder: Encoder.AsObject[AuthTokenError] = {
    Encoder.AsObject.instance[AuthTokenError] {
      case AuthTokenHttpError(r)          =>
        JsonObject(keywords.tpe := "AuthTokenHttpError", "reason" := r.reason)
      case AuthTokenNotFoundInResponse(r) =>
        JsonObject(keywords.tpe -> "AuthTokenNotFoundInResponse".asJson, "reason" := r.message)
      case r: RealmIsDeprecated           =>
        JsonObject(keywords.tpe := "RealmIsDeprecated", "reason" := r.getMessage)
    }
  }

  implicit val identityErrorJsonLdEncoder: JsonLdEncoder[AuthTokenError] =
    JsonLdEncoder.computeFromCirce(ContextValue(contexts.error))
}
