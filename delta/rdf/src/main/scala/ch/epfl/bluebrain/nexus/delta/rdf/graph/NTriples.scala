package ch.epfl.bluebrain.nexus.delta.rdf.graph

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.BNode

/**
  * A placeholder for the N-Triples Graph format output https://www.w3.org/TR/n-triples/
  *
  * @param value    the output
  * @param rootNode the root node of the graph
  */
final case class NTriples(value: String, rootNode: IriOrBNode) {

  /**
    * Merge the current NTriples with the passed ones, appending the passed ones to the bottom.
    * The duplicates are removed.
    */
  def ++(that: NTriples): NTriples =
    that.copy(value = s"$value\n${that.value}".split("\n").filter(_.trim.nonEmpty).toSet.mkString("\n"))

  override def toString: String = value
}

object NTriples {
  val empty: NTriples = NTriples("", BNode.random)
}
