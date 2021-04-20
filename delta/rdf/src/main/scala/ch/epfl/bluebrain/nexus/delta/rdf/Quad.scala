package ch.epfl.bluebrain.nexus.delta.rdf

import org.apache.jena.graph.Node

object Quad {

  /**
    * An RDF Quad.
    * Graph must be an Iri or a blank node.
    * Subject must be an Iri or a blank node.
    * Predicate must be an Iri.
    * Object must be an Iri, a blank node or a Literal.
    */
  type Quad = (Node, Node, Node, Node)
}
