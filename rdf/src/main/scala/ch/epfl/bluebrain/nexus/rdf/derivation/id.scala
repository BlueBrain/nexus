package ch.epfl.bluebrain.nexus.rdf.derivation

import ch.epfl.bluebrain.nexus.rdf.Iri.AbsoluteIri

import scala.annotation.StaticAnnotation

final case class id(value: AbsoluteIri) extends StaticAnnotation
