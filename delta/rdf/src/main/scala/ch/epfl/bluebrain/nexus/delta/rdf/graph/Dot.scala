package ch.epfl.bluebrain.nexus.delta.rdf.graph

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode

/**
  * A placeholder for the DOT Graph format output https://graphviz.org/doc/info/lang.html
  *
  * @param value the output
  * @param rootNode  the root node of the graph
  */
final case class Dot(value: String, rootNode: IriOrBNode) {
  override def toString: String = value
}
