package ch.epfl.bluebrain.nexus.delta.sdk.error

import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClientError
import io.circe.syntax.{EncoderOps, KeyOps}
import io.circe.{DecodingFailure, Encoder, JsonObject}

sealed abstract class TokenError(reason: String) extends SDKError {
  override def getMessage: String = reason
}

object TokenError {

  /**
    * Signals that an HTTP error occurred when fetching the token
    */
  final case class TokenHttpError(cause: HttpClientError)
      extends TokenError(s"HTTP error when requesting token: ${cause.reason}")

  /**
    * Signals that the token was missing from the authentication response
    */
  final case class TokenNotFoundInResponse(failure: DecodingFailure)
      extends TokenError(s"Token not found in auth response: ${failure.reason}")

  implicit val identityErrorEncoder: Encoder.AsObject[TokenError] = {
    Encoder.AsObject.instance[TokenError] {
      case TokenHttpError(r)          =>
        JsonObject(keywords.tpe := "TokenHttpError", "reason" := r.reason)
      case TokenNotFoundInResponse(r) =>
        JsonObject(keywords.tpe -> "TokenNotFoundInResponse".asJson, "reason" := r.message)
    }
  }

  implicit val identityErrorJsonLdEncoder: JsonLdEncoder[TokenError] =
    JsonLdEncoder.computeFromCirce(ContextValue(contexts.error))
}
