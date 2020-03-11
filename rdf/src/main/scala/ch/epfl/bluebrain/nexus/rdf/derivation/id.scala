package ch.epfl.bluebrain.nexus.rdf.derivation

import ch.epfl.bluebrain.nexus.rdf.iri.Iri.Uri

import scala.annotation.StaticAnnotation

final case class id(value: Uri) extends StaticAnnotation
