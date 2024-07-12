package ch.epfl.bluebrain.nexus.delta.sdk.jws

import akka.http.scaladsl.model.StatusCodes
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClassUtils
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.BNode
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.error.SDKError
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.HttpResponseFields
import io.circe.syntax.KeyOps
import io.circe.{Encoder, JsonObject}

/**
  * Rejections related to JWS operations
  *
  * @param reason
  *   a descriptive message for reasons why the JWS operations failed
  */
sealed abstract class JWSError(val reason: String) extends SDKError {
  override def getMessage: String = reason
}

object JWSError {

  final case object UnconfiguredJWS
      extends JWSError("JWS config is incorrect or missing. Please contact your administrator.")

  final case object InvalidJWSPayload extends JWSError("Signature missing, flattened JWS format expected")

  final case object JWSSignatureExpired extends JWSError("The payload expired")

  implicit val jwsErrorHttpResponseFields: HttpResponseFields[JWSError] = HttpResponseFields.fromStatusAndHeaders {
    case InvalidJWSPayload   => (StatusCodes.BadRequest, Seq.empty)
    case JWSSignatureExpired => (StatusCodes.Forbidden, Seq.empty)
    case UnconfiguredJWS     => (StatusCodes.InternalServerError, Seq.empty)
  }

  implicit val jwsErrorEncoder: Encoder.AsObject[JWSError] =
    Encoder.AsObject.instance { e =>
      val tpe = ClassUtils.simpleName(e)
      JsonObject(keywords.tpe := tpe, "reason" := e.reason)
    }

  implicit final val jwsErrorJsonLdEncoder: JsonLdEncoder[JWSError] =
    JsonLdEncoder.computeFromCirce(id = BNode.random, ctx = ContextValue(contexts.error))

}
