package ch.epfl.bluebrain.nexus.rdf.syntax

import ch.epfl.bluebrain.nexus.rdf.{Iri, Vocabulary}
import ch.epfl.bluebrain.nexus.rdf.Iri.{AbsoluteIri, Url, Urn}

trait IriSyntax {
  implicit final def iriContext(sc: StringContext): IriContext = new IriContext(sc)
}

final class IriContext(val sc: StringContext) extends AnyVal {
  def url(args: Any*): Url =
    Url.unsafe(sc.s(args: _*))
  def urn(args: Any*): Urn =
    Urn.unsafe(sc.s(args: _*))
  def iri(args: Any*): Iri =
    Iri.unsafe(sc.s(args: _*))
  def nxv(args: Any*): AbsoluteIri =
    Vocabulary.nxv.base + sc.s(args: _*)
  def schema(args: Any*): AbsoluteIri =
    Url.unsafe(Vocabulary.schema.base + sc.s(args: _*))
}
