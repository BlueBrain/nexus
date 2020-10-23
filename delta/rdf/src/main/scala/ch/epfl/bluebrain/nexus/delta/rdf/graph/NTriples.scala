package ch.epfl.bluebrain.nexus.delta.rdf.graph

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode

/**
  * A placeholder for the N-Triples Graph format output https://www.w3.org/TR/n-triples/
  *
  * @param value    the output
  * @param rootNode the root node of the graph
  */
final case class NTriples(value: String, rootNode: IriOrBNode) {
  override def toString: String = value
}
