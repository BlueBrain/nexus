package ch.epfl.bluebrain.nexus.delta.rdf

import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolutionError

sealed abstract class RdfError(val reason: String, details: Option[String] = None) extends Exception {
  override def fillInStackTrace(): RdfError = this
  override def getMessage: String           = details.fold(reason)(d => s"$reason\nDetails: $d")
}

object RdfError {

  /**
    * An unexpected JSON-LD document
    */
  final case class UnexpectedJsonLd(details: String) extends RdfError("Unexpected JSON-LD document.", Some(details))

  /**
    * An unexpected JSON-LD @context document
    */
  final case class UnexpectedJsonLdContext(details: String)
      extends RdfError("Unexpected JSON-LD @context document.", Some(details))

  /**
    * An error while resolving remote @context
    */
  final case class RemoteContextError(error: RemoteContextResolutionError) extends RdfError(error.getMessage)

  /**
    * An unexpected conversion error
    */
  final case class ConversionError(details: String, stage: String)
      extends RdfError(s"Error on the conversion stage '$stage'", Some(details))

  /**
    * Invalid Iri
    */
  final case class InvalidIri(err: String) extends RdfError(err)

  /**
    * Unexpected Iri value
    */
  final case class UnexpectedIriOrBNode(expected: IriOrBNode, found: IriOrBNode)
      extends RdfError(s"Unexpected Iri or blank node value. Expected '$expected', found '$found'")
}
