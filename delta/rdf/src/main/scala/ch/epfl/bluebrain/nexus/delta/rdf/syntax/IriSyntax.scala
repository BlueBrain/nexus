package ch.epfl.bluebrain.nexus.delta.rdf.syntax

import ch.epfl.bluebrain.nexus.delta.rdf._
import org.apache.jena.iri.IRI

trait IriSyntax {
  implicit final def iriStringContextSyntax(sc: StringContext): IriStringContextOps = new IriStringContextOps(sc)
  implicit final def iriStringSyntax(string: String): IriStringOps                  = new IriStringOps(string)
}

final class IriStringContextOps(private val sc: StringContext) extends AnyVal {
  def iri(args: Any*): IRI = iriUnsafe(sc.s(args: _*))
}

final class IriStringOps(private val string: String) extends AnyVal {
  def toIri: Either[String, IRI] = iri(string)
}
