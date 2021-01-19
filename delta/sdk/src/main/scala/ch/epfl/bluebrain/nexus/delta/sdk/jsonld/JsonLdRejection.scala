package ch.epfl.bluebrain.nexus.delta.sdk.jsonld

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.RdfError
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoderError

sealed abstract class JsonLdRejection(val reason: String) extends Product with Serializable

object JsonLdRejection {

  sealed abstract class InvalidJsonLdRejection(r: String) extends JsonLdRejection(r)

  /**
    * Rejection returned when the passed id does not match the id on the payload
    *
    * @param id        the passed identifier
    * @param payloadId the identifier on the payload
    */
  final case class UnexpectedId(id: Iri, payloadId: Iri)
      extends InvalidJsonLdRejection(s"Id '$id' does not match the id on payload '$payloadId'.")

  /**
    * Rejection when converting the source Json to JsonLD fails
    *
    * @param id           the passed identifier
    * @param rdfError     the rdf error
    */
  final case class InvalidJsonLdFormat(id: Option[Iri], rdfError: RdfError)
      extends InvalidJsonLdRejection(
        s"Storage ${id.fold("")(id => s"'$id'")} has invalid JSON-LD payload. Error: '${rdfError.reason}'"
      )

  /**
    * Rejection when attempting to decode an expanded JsonLD as a case class
    * @param error the decoder error
    */
  final case class DecodingFailed(error: JsonLdDecoderError) extends JsonLdRejection(error.getMessage)
}
