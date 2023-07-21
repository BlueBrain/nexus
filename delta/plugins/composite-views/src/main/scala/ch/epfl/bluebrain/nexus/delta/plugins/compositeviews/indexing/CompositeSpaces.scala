package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.indexing

import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.BlazegraphClient
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.indexing.{BlazegraphSink, GraphResourceToNTriples}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.CompositeSink
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.indexing.CompositeViewDef.ActiveViewDef
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewProjection
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewProjection.{ElasticSearchProjection, SparqlProjection}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.ElasticSearchClient.Refresh
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.{ElasticSearchClient, IndexLabel}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing.{ElasticSearchSink, GraphResourceToDocument}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sourcing.config.BatchConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Operation.Sink
import com.typesafe.scalalogging.Logger
import monix.bio.Task

/**
  * Defines the pipes, sinks for the indexing progress as well as the init and destroy tasks for a composite view
  * @param init
  *   the task to create the different namespaces and indices
  * @param destroy
  *   the task to destroy the different namespaces and indices
  * @param commonSink
  *   the sink for the common sparql space
  * @param targetSink
  *   the function to create a sink for a [[CompositeViewProjection]]
  */
final case class CompositeSpaces(
    init: Task[Unit],
    destroy: Task[Unit],
    commonSink: Sink,
    targetSink: CompositeViewProjection => Sink
)

object CompositeSpaces {

  private val logger: Logger = Logger[CompositeSpaces]

  trait Builder {

    /**
      * Compute the spaces for the given view
      * @param view
      *   the active view
      */
    def apply(view: ActiveViewDef): CompositeSpaces
  }

  object Builder {
    def apply(
        prefix: String,
        esClient: ElasticSearchClient,
        esBatchConfig: BatchConfig,
        blazeClient: BlazegraphClient,
        blazeBatchConfig: BatchConfig
    )(implicit base: BaseUri, rcr: RemoteContextResolution): CompositeSpaces.Builder = (view: ActiveViewDef) => {

      // Operations and sinks for the common space
      val common       = commonNamespace(view.uuid, view.rev, prefix)
      val commonSink   = BlazegraphSink(blazeClient, blazeBatchConfig, common)
      val createCommon = blazeClient.createNamespace(common)
      val deleteCommon = blazeClient.deleteNamespace(common)

      // Create the Blazegraph sink
      def createBlazeSink(namespace: String): SparqlProjection => Sink = { target =>
        val blazeSink = BlazegraphSink(blazeClient, blazeBatchConfig, namespace)
        new CompositeSink(
          QueryGraph(blazeClient, common, target.query),
          GraphResourceToNTriples.graphToNTriples,
          blazeSink.apply,
          blazeBatchConfig.maxElements,
          blazeBatchConfig.maxInterval
        )
      }

      // Create the Elasticsearch index
      def createEsSink(index: IndexLabel): ElasticSearchProjection => Sink = { target =>
        val esSink =
          ElasticSearchSink.states(esClient, esBatchConfig.maxElements, esBatchConfig.maxInterval, index, Refresh.False)
        new CompositeSink(
          QueryGraph(blazeClient, common, target.query),
          new GraphResourceToDocument(target.context, target.includeContext).graphToDocument,
          esSink.apply,
          esBatchConfig.maxElements,
          esBatchConfig.maxInterval
        )
      }

      // Compute the init and destroy operations as well as the sink for the different projections of the composite views
      val start: (Task[Unit], Task[Unit], Map[Iri, Sink]) = (createCommon.void, deleteCommon.void, Map.empty[Iri, Sink])
      val (init, destroy, sinkMap)                        = view.value.projections.foldLeft(start) {
        case ((create, delete, sinkMap), p: ElasticSearchProjection) =>
          val index = projectionIndex(p, view.uuid, view.rev, prefix)
          (
            create >> esClient.createIndex(index, Some(p.mapping), p.settings).void,
            delete >> esClient.deleteIndex(index).void,
            sinkMap.updated(p.id, createEsSink(index)(p))
          )
        case ((create, delete, sinkMap), s: SparqlProjection)        =>
          val namespace = projectionNamespace(s, view.uuid, view.rev, prefix)
          (
            create >> blazeClient.createNamespace(namespace).void,
            delete >> blazeClient.deleteNamespace(namespace).void,
            sinkMap.updated(s.id, createBlazeSink(namespace)(s))
          )
      }

      CompositeSpaces(
        Task.delay(logger.debug("Creating namespaces and indices for composite view {}", view.ref)) >> init,
        Task.delay(logger.debug("Deleting namespaces and indices for composite view {}", view.ref)) >> destroy,
        commonSink,
        (p: CompositeViewProjection) => sinkMap(p.id)
      )
    }
  }
}
