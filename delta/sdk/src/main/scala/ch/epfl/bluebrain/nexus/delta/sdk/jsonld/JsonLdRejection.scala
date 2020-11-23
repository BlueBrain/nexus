package ch.epfl.bluebrain.nexus.delta.sdk.jsonld

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.RdfError

sealed trait JsonLdRejection extends Product with Serializable

object JsonLdRejection {

  /**
    * Rejection returned when providing an id that cannot be resolved to an Iri
    *
    * @param id the identifier
    */
  final case class InvalidId(id: String) extends JsonLdRejection

  /**
    * Rejection returned when the passed id does not match the id on the payload
    *
    * @param id        the passed identifier
    * @param payloadId the identifier on the payload
    */
  final case class UnexpectedId(id: Iri, payloadId: Iri) extends JsonLdRejection

  /**
    * Rejection when converting the source Json to JsonLD fails
    *
    * @param id           the passed identifier
    * @param rdfError     the rdf error
    */
  final case class InvalidJsonLdFormat(id: Option[Iri], rdfError: RdfError) extends JsonLdRejection
}
