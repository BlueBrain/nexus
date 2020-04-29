package ch.epfl.bluebrain.nexus.rdf.jsonld

import ch.epfl.bluebrain.nexus.rdf.iri.Iri.Uri

/**
  * Options to pass to the JsonLd processing algorithm
  *
  * @param base           the base value to use when expanding or compacting the document if the @context @base keyword is not set
  * @param useNativeTypes when converting from Graph -> Json, avoid adding an explicit @type for navtive Json values
  * @param useRdfType     when converting from Graph -> Json, rdf:type properties will be kept as Uris rather than use @type
  */
final case class JsonLdOptions(base: Option[Uri], useNativeTypes: Boolean = false, useRdfType: Boolean = false)

object JsonLdOptions {
  val empty: JsonLdOptions = JsonLdOptions(base = None)

  sealed trait RdfDirection extends Product with Serializable
}
