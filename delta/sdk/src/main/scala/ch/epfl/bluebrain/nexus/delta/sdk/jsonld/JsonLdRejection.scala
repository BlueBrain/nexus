package ch.epfl.bluebrain.nexus.delta.sdk.jsonld

import akka.http.scaladsl.model.StatusCodes
import ch.epfl.bluebrain.nexus.delta.kernel.error.Rejection
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClassUtils
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.RdfError
import ch.epfl.bluebrain.nexus.delta.rdf.RdfError.ConversionError
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoderError
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.HttpResponseFields
import io.circe.syntax.EncoderOps
import io.circe.{Encoder, JsonObject}

sealed abstract class JsonLdRejection(val reason: String) extends Rejection

object JsonLdRejection {

  sealed abstract class InvalidJsonLdRejection(r: String) extends JsonLdRejection(r)

  /**
    * Rejection returned when the passed id does not match the id on the payload
    *
    * @param id
    *   the passed identifier
    * @param payloadId
    *   the identifier on the payload
    */
  final case class UnexpectedId(id: Iri, payloadId: Iri)
      extends InvalidJsonLdRejection(s"Id '$id' does not match the id on payload '$payloadId'.")

  /**
    * Rejection returned when the passed id is blank
    */
  case object BlankId extends InvalidJsonLdRejection(s"Id was blank.")

  /**
    * Rejection when converting the source Json to JsonLD fails
    *
    * @param id
    *   the passed identifier
    * @param rdfError
    *   the rdf error
    */
  final case class InvalidJsonLdFormat(id: Option[Iri], rdfError: RdfError)
      extends InvalidJsonLdRejection(
        s"Resource ${id.fold("")(id => s"'$id'")} has invalid JSON-LD payload. Error: '${rdfError.reason}'"
      )

  /**
    * Rejection when attempting to decode an expanded JsonLD as a case class
    * @param error
    *   the decoder error
    */
  final case class DecodingFailed(error: JsonLdDecoderError) extends JsonLdRejection(error.getMessage)

  implicit val jsonLdRejectionEncoder: Encoder.AsObject[JsonLdRejection] =
    Encoder.AsObject.instance { r =>
      val tpe = ClassUtils.simpleName(r)
      val obj = JsonObject.empty.add(keywords.tpe, tpe.asJson).add("reason", r.reason.asJson)
      r match {
        case InvalidJsonLdFormat(_, ConversionError(details, _)) => obj.add("details", details.asJson)
        case InvalidJsonLdFormat(_, rdf)                         => obj.add("rdf", rdf.asJson)
        case _                                                   => obj
      }
    }

  implicit val resourceRejectionJsonLdEncoder: JsonLdEncoder[JsonLdRejection] =
    JsonLdEncoder.computeFromCirce(ContextValue(contexts.error))

  implicit val responseFieldsJsonLd: HttpResponseFields[JsonLdRejection] =
    HttpResponseFields(_ => StatusCodes.BadRequest)
}
