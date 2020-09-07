package ch.epfl.bluebrain.nexus.delta.rdf

import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolutionError
import org.apache.jena.iri.IRI

@SuppressWarnings(Array("IncorrectlyNamedExceptions"))
sealed abstract class RdfError(reason: String, details: Option[String] = None) extends Exception {
  override def fillInStackTrace(): RdfError = this
  override def getMessage: String           = details.fold(reason)(d => s"$reason\nDetails: $d")
}

@SuppressWarnings(Array("IncorrectlyNamedExceptions"))
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
    * Invalid IRI
    */
  final case class InvalidIri(iri: String) extends RdfError(s"The value '$iri' is not an Iri")

  /**
    * Unexpected IRI value
    */
  final case class UnexpectedIri(expected: IRI, found: IRI)
      extends RdfError(s"Unexpected IRI value. Expected '$expected', found '$found'")

  /**
    * The JSON-LD document // RDF Dataset does not have a root node
    */
  final case object RootIriNotFound extends RdfError(s"Root IRI not found")
}
