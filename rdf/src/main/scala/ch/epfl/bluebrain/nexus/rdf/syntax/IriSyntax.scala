package ch.epfl.bluebrain.nexus.rdf.syntax

import ch.epfl.bluebrain.nexus.rdf.iri.Iri._
import ch.epfl.bluebrain.nexus.rdf.iri.Path.{Segment, Slash}
import ch.epfl.bluebrain.nexus.rdf.iri.{Iri, Path}

trait IriSyntax {
  implicit final def iriContext(sc: StringContext): IriContext            = new IriContext(sc)
  implicit final def stringPathOpsContext(segment: String): StringPathOps = new StringPathOps(segment)
}

final class IriContext(private val sc: StringContext) extends AnyVal {
  def url(args: Any*): Url =
    Url.unsafe(sc.s(args: _*))
  def uri(args: Any*): Uri =
    Uri.unsafe(sc.s(args: _*))
  def urn(args: Any*): Urn =
    Urn.unsafe(sc.s(args: _*))
  def iri(args: Any*): Iri =
    Iri.unsafe(sc.s(args: _*))
}

final class StringPathOps(private val segment: String) extends AnyVal {

  /**
    * @param string the segment to be appended to a previous ''segment''
    * @return / segment / string
    */
  def /(string: String): Path = Segment(string, Slash(Segment(segment, Path./)))
}
