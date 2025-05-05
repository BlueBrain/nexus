package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.indexing

import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategyConfig
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.SparqlClient
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.indexing.SparqlSink
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.CompositeSink
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.config.CompositeViewsConfig.SinkConfig.SinkConfig
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.indexing.CompositeViewDef.ActiveViewDef
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewProjection
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewProjection.{ElasticSearchProjection, SparqlProjection}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.ElasticSearchClient
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Operation.Sink
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.config.BatchConfig

/**
  * Defines the sinks for the indexing progress for a composite view
  */
trait CompositeSinks {

  /**
    * The sink for the current namespace
    */
  def commonSink(view: ActiveViewDef): Sink

  /**
    * The sink for a given projection
    */
  def projectionSink(view: ActiveViewDef, target: CompositeViewProjection): Sink
}

object CompositeSinks {

  def apply(
      prefix: String,
      esClient: ElasticSearchClient,
      esBatch: BatchConfig,
      sparqlClient: SparqlClient,
      sparqlBatch: BatchConfig,
      sinkConfig: SinkConfig,
      retryStrategy: RetryStrategyConfig
  )(implicit base: BaseUri, rcr: RemoteContextResolution): CompositeSinks = new CompositeSinks {

    /**
      * The sink for the current namespace
      */
    override def commonSink(view: ActiveViewDef): Sink = {
      val common = commonNamespace(view.uuid, view.indexingRev, prefix)
      SparqlSink(sparqlClient, retryStrategy, sparqlBatch, common)
    }

    /**
      * The sink for a given projection
      */
    override def projectionSink(view: ActiveViewDef, target: CompositeViewProjection): Sink = {
      val common = commonNamespace(view.uuid, view.indexingRev, prefix)
      target match {
        case e: ElasticSearchProjection =>
          val index = projectionIndex(e, view.uuid, prefix)
          CompositeSink.elasticSink(sparqlClient, esClient, index, common, esBatch, sinkConfig, retryStrategy).apply(e)
        case s: SparqlProjection        =>
          val namespace = projectionNamespace(s, view.uuid, prefix)
          CompositeSink.sparqlSink(sparqlClient, namespace, common, sparqlBatch, sinkConfig, retryStrategy).apply(s)
      }
    }
  }
}
