package ch.epfl.bluebrain.nexus.delta.sdk.error

import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClientError
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
    * Signals that the expiry was missing from the authentication response
    */
  final case class ExpiryNotFoundInResponse(failure: DecodingFailure)
      extends AuthTokenError(s"Expiry not found in auth response: ${failure.reason}")

  implicit val identityErrorEncoder: Encoder.AsObject[AuthTokenError] = {
    Encoder.AsObject.instance[AuthTokenError] {
      case AuthTokenHttpError(r)          =>
        JsonObject(keywords.tpe := "AuthTokenHttpError", "reason" := r.reason)
      case AuthTokenNotFoundInResponse(r) =>
        JsonObject(keywords.tpe -> "AuthTokenNotFoundInResponse".asJson, "reason" := r.message)
      case ExpiryNotFoundInResponse(r)    =>
        JsonObject(keywords.tpe -> "ExpiryNotFoundInResponse".asJson, "reason" := r.message)
    }
  }

  implicit val identityErrorJsonLdEncoder: JsonLdEncoder[AuthTokenError] =
    JsonLdEncoder.computeFromCirce(ContextValue(contexts.error))
}
