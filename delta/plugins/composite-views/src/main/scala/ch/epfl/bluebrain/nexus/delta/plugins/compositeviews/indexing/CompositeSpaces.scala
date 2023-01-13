package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.indexing

import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.BlazegraphClient
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.indexing.BlazegraphSink
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.indexing.CompositeViewDef.ActiveViewDef
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewProjection
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewProjection.{ElasticSearchProjection, SparqlProjection}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.ElasticSearchClient.Refresh
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.{ElasticSearchClient, IndexLabel}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing.ElasticSearchSink
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.query.SparqlQuery.SparqlConstructQuery
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sourcing.config.BatchConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Operation
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
  * @param queryPipe
  *   the function to create the query pipe from a [[SparqlConstructQuery]]
  * @param targetSink
  *   the function to create a sink for a [[CompositeViewProjection]]
  */
final case class CompositeSpaces(
    init: Task[Unit],
    destroy: Task[Unit],
    commonSink: Sink,
    queryPipe: SparqlConstructQuery => Operation,
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
    )(implicit base: BaseUri): CompositeSpaces.Builder = (view: ActiveViewDef) => {

      // Create the Blazegraph sink
      def createBlazeSink(namespace: String) = BlazegraphSink(blazeClient, blazeBatchConfig, namespace)
      // Create the Elasticsearch index
      def createEsSink(index: IndexLabel)    =
        ElasticSearchSink.states(esClient, esBatchConfig.maxElements, esBatchConfig.maxInterval, index, Refresh.False)

      // Operations and sinks for the common space
      val common       = commonNamespace(view.uuid, view.rev, prefix)
      val commonSink   = createBlazeSink(common)
      val queryPipe    = (query: SparqlConstructQuery) => new QueryGraph(blazeClient, common, query)
      val createCommon = blazeClient.createNamespace(common)
      val deleteCommon = blazeClient.deleteNamespace(common)

      // Compute the init and destroy operations as well as the sink for the different projections of the composite views
      val start: (Task[Unit], Task[Unit], Map[Iri, Sink]) = (createCommon.void, deleteCommon.void, Map.empty[Iri, Sink])
      val (init, destroy, sinkMap)                        = view.value.projections.foldLeft(start) {
        case ((create, delete, sinkMap), p: ElasticSearchProjection) =>
          val index = projectionIndex(p, view.uuid, view.rev, prefix)
          (
            create >> esClient.createIndex(index, Some(p.mapping), p.settings).void,
            delete >> esClient.deleteIndex(index).void,
            sinkMap.updated(p.id, createEsSink(index))
          )
        case ((create, delete, sinkMap), s: SparqlProjection)        =>
          val namespace = projectionNamespace(s, view.uuid, view.rev, prefix)
          (
            create >> blazeClient.createNamespace(namespace).void,
            delete >> blazeClient.deleteNamespace(namespace).void,
            sinkMap.updated(s.id, createBlazeSink(namespace))
          )
      }

      CompositeSpaces(
        Task.delay(logger.debug("Creating namespaces and indices for composite view {}", view.ref)) >> init,
        Task.delay(logger.debug("Deleting namespaces and indices for composite view {}", view.ref)) >> destroy,
        commonSink,
        queryPipe,
        (p: CompositeViewProjection) => sinkMap(p.id)
      )
    }
  }
}
