package ch.epfl.bluebrain.nexus.kg.directives

import ch.epfl.bluebrain.nexus.rdf.Iri.AbsoluteIri

/**
  * AbsoluteIri that gets expanded using the @vocab instead of the @base
  * on the default case
  *
  * @param value the absolute iri
  */
final private[directives] case class VocabAbsoluteIri(value: AbsoluteIri)
