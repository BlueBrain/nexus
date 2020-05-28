package ch.epfl.bluebrain.nexus.rdf.syntax

import ch.epfl.bluebrain.nexus.rdf.Iri.AbsoluteIri
import ch.epfl.bluebrain.nexus.rdf.Node.{BNode, IriNode}

trait NodeSyntax {
  implicit final def nodeContext(sc: StringContext): NodeContext = new NodeContext(sc)
}

final class NodeContext(val sc: StringContext) extends AnyVal {
  def b(args: Any*): BNode = {
    val string = sc.s(args: _*)
    BNode(string).getOrElse(throw new IllegalArgumentException(s"Illegal blank node identifier '$string'"))
  }
  def in(args: Any*): IriNode = {
    val string = sc.s(args: _*)
    IriNode(AbsoluteIri.unsafe(string))
  }
}
