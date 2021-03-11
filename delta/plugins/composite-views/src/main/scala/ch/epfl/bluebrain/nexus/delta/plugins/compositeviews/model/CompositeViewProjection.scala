package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model

import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewValue.IndexingBlazegraphViewValue
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewValue.IndexingElasticSearchViewValue
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue

/**
  * A target projection for [[CompositeView]].
  */
sealed trait CompositeViewProjection extends Product with Serializable {

  /**
    * SPARQL query used to create values indexed into the projection.
    */
  def query: String
}

object CompositeViewProjection {

  /**
    * An ElasticSearch projection for [[CompositeView]].
    *
    * @param query    SPARQL query used to create values indexed into the projection.
    * @param view     target ElasticSearch View
    * @param context  context used to create ElasticSearch document
    */
  final case class ElasticSearchProjection(query: String, view: IndexingElasticSearchViewValue, context: ContextValue)
      extends CompositeViewProjection

  /**
    * A Sparql projection for [[CompositeView]].
    *
    * @param query    SPARQL query used to create values indexed into the projection.
    * @param view     target Blazegraph view
    */
  final case class SparqlProjection(query: String, view: IndexingBlazegraphViewValue) extends CompositeViewProjection

}
