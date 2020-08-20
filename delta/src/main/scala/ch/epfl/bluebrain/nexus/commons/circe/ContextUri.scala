package ch.epfl.bluebrain.nexus.commons.circe

import ch.epfl.bluebrain.nexus.rdf.Iri.AbsoluteIri

/**
  * Wrapper class holding a context URI.
  *
  * @param value the underlying context URI
  */
final case class ContextUri(value: AbsoluteIri) {
  override def toString: String = value.toString
}
