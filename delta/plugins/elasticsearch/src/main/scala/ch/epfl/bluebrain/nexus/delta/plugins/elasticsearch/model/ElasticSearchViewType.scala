package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv

/**
  * Enumeration of ElasticSearch view types.
  */
sealed trait ElasticSearchViewType extends Product with Serializable {

  /**
    * @return the type id
    */
  def iri: Iri
}

object ElasticSearchViewType {

  /**
    * ElasticSearch view that indexes resources as documents.
    */
  final case object ElasticSearch extends ElasticSearchViewType {
    override val toString: String = "ElasticSearchView"
    override val iri: Iri         = nxv + toString
  }

  /**
    * ElasticSearch view that delegates queries to a collection of existing ElasticSearch views based on access.
    */
  final case object AggregateElasticSearch extends ElasticSearchViewType {
    override val toString: String = "AggregateElasticSearchView"
    override val iri: Iri         = nxv + toString
  }
}
