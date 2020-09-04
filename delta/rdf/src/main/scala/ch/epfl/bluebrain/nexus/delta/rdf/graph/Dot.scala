package ch.epfl.bluebrain.nexus.delta.rdf.graph

import org.apache.jena.iri.IRI

/**
  * A placeholder for the DOT Graph format output https://graphviz.org/doc/info/lang.html
  *
 * @param value the output
  * @param root  the root node of the graph
  */
final case class Dot(value: String, root: IRI) {
  override def toString: String = value
}
