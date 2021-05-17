package ch.epfl.bluebrain.nexus.delta.rdf

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolutionError
import io.circe.Encoder
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredEncoder

import scala.annotation.nowarn

sealed abstract class RdfError(val reason: String, details: Option[String] = None) extends Exception {
  override def fillInStackTrace(): RdfError = this
  override def getMessage: String           = details.fold(reason)(d => s"$reason\nDetails: $d")
}

@nowarn("cat=unused")
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
  final case class RemoteContextError(error: RemoteContextResolutionError)
      extends RdfError(error.getMessage, error.getDetails)

  /**
    * An unexpected conversion error
    */
  final case class ConversionError(details: String, stage: String)
      extends RdfError(s"Error on the conversion stage '$stage'", Some(details))

  /**
    * Missing required predicate.
    */
  final case class MissingPredicate(predicate: Iri)
      extends RdfError(s"Required predicate $predicate is missing from graph.", None)

  /**
    * Invalid Iri
    */
  final case object InvalidIri extends RdfError(s"keyword '${keywords.id}' could not be converted to an Iri")
  type InvalidIri = InvalidIri.type

  /**
    * Unexpected Iri value
    */
  final case class UnexpectedIriOrBNode(expected: IriOrBNode, found: IriOrBNode)
      extends RdfError(s"Unexpected Iri or blank node value. Expected '$expected', found '$found'")

  /**
    * Circular dependency on remote context resolution
    */
  final case class RemoteContextCircularDependency(iri: Iri)
      extends RdfError(
        s"Remote context '$iri' has already been resolved once. Circular dependency detected"
      )

  implicit private val config: Configuration = Configuration.default.withDiscriminator(keywords.tpe)

  implicit val rdfErrorEncoder: Encoder.AsObject[RdfError] = deriveConfiguredEncoder[RdfError]
}
