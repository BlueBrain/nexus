package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv

/**
  * Enumeration of Blazegraph view types.
  */
sealed trait BlazegraphViewType extends Product with Serializable {

  /**
    * @return RDF types of the view
    */
  def types: Set[Iri]

}

object BlazegraphViewType {

  /**
    * Blazegraph view that indexes resources as triples.
    */
  final case object IndexingBlazegraphView extends BlazegraphViewType {
    override val toString: String = "BlazegraphView"
    override val types: Set[Iri]  = Set(nxv + toString, nxv + "SparqlView")
  }

  /**
    * Blazegraph view that delegates queries to a collections of existing Blazegraph views based on access.
    */
  final case object AggregateBlazegraphView extends BlazegraphViewType {
    override val toString: String = "AggregateBlazegraphView"
    override val types: Set[Iri]  = Set(nxv + toString, nxv + "AggregateSparqlView")
  }
}
