package ch.epfl.bluebrain.nexus.delta.rdf.graph

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode

/**
  * A placeholder for the N-Quads Graph format output https://www.w3.org/TR/n-quads/
  *
  * @param value
  *   the output
  * @param rootNode
  *   the root node of the graph
  */
final case class NQuads(value: String, rootNode: IriOrBNode) {
  override def toString: String = value
}
