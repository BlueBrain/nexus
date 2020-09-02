package ch.epfl.bluebrain.nexus.delta.rdf.jsonld

import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolutionError
import org.apache.jena.iri.IRI

@SuppressWarnings(Array("IncorrectlyNamedExceptions"))
sealed abstract class JsonLdError(reason: String) extends Exception {
  override def fillInStackTrace(): JsonLdError = this
  override def getMessage: String              = s"Reason: '$reason'"
}

@SuppressWarnings(Array("IncorrectlyNamedExceptions"))
object JsonLdError {

  /**
    * An unexpected JSON-LD document
    */
  final case class UnexpectedJsonLd(reason: String)
      extends JsonLdError(s"Unexpected JSON-LD document. Details: $reason")

  /**
    * An unexpected JSON-LD @context document
    */
  final case class UnexpectedJsonLdContext(reason: String)
      extends JsonLdError(s"Unexpected JSON-LD @context document. Details: $reason")

  /**
    * An error while resolving remote @context
    */
  final case class RemoteContextError(error: RemoteContextResolutionError) extends JsonLdError(error.getMessage)

  /**
    * An unexpected error on JsonLdApi
    */
  final case class JsonLdApiError(reason: String, stage: String)
      extends JsonLdError(s"Error when calling the JsonApi on the conversion stage '$stage'. Details: $reason")

  /**
    * Invalid Iri inside a JSON-LD document
    */
  final case class InvalidIri(iri: String) extends JsonLdError(s"The value '$iri' is not an Iri")

  /**
    * Unexpected IRI value
    */
  final case class UnexpectedIri(expected: IRI, found: IRI)
      extends JsonLdError(s"Unexpected IRI value. Expected '$expected', found '$found'")

  /**
    * The JSON-LD document does not have an @id value
    */
  final case object IdNotFound extends JsonLdError(s"The JSON-LD document does not have an @id value")
}
