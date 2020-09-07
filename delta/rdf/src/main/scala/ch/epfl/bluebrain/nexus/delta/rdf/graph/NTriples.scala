package ch.epfl.bluebrain.nexus.delta.rdf.graph

import org.apache.jena.iri.IRI

/**
  * A placeholder for the N-Triples Graph format output https://www.w3.org/TR/n-triples/
  *
 * @param value the output
  * @param root  the root node of the graph
  */
final case class NTriples(value: String, root: IRI) {
  override def toString: String = value
}
