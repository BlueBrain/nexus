package ch.epfl.bluebrain.nexus.rdf.syntax

import ch.epfl.bluebrain.nexus.rdf.Node.{BNode, IriNode}
import ch.epfl.bluebrain.nexus.rdf.iri.Iri.Uri

trait NodeSyntax {
  implicit final def nodeContext(sc: StringContext): NodeContext = new NodeContext(sc)
}

final class NodeContext(private val sc: StringContext) extends AnyVal {
  def b(args: Any*): BNode = {
    val string = sc.s(args: _*)
    BNode(string).getOrElse(throw new IllegalArgumentException(s"Illegal blank node identifier '$string'"))
  }
  def in(args: Any*): IriNode = {
    val string = sc.s(args: _*)
    IriNode(Uri.unsafe(string))
  }
}
