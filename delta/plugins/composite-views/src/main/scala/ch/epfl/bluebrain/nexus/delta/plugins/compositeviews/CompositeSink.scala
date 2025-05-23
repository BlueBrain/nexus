package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews

import cats.effect.IO
import cats.implicits.*
import ch.epfl.bluebrain.nexus.delta.kernel.error.HttpConnectivityError
import ch.epfl.bluebrain.nexus.delta.kernel.kamon.KamonMetricComponent
import ch.epfl.bluebrain.nexus.delta.kernel.syntax.kamonSyntax
import ch.epfl.bluebrain.nexus.delta.kernel.{Logger, RetryStrategy, RetryStrategyConfig}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.SparqlClient
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.SparqlClientError.SparqlQueryError
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.indexing.{GraphResourceToNTriples, SparqlSink}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.config.CompositeViewsConfig.SinkConfig
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.config.CompositeViewsConfig.SinkConfig.SinkConfig
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.indexing.{BatchQueryGraph, SingleQueryGraph}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewProjection.{ElasticSearchProjection, SparqlProjection}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.{ElasticSearchClient, IndexLabel, Refresh}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing.{ElasticSearchSink, GraphResourceToDocument}
import ch.epfl.bluebrain.nexus.delta.rdf.graph.Graph
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.{JsonLdApi, JsonLdOptions, TitaniumJsonLdApi}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.query.SparqlQuery.SparqlConstructQuery
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.*
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sourcing.state.GraphResource
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.{DroppedElem, FailedElem, SuccessElem}
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Operation.Sink
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.config.BatchConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.{Elem, ElemChunk}
import fs2.Chunk
import shapeless.Typeable

/**
  * A composite sink handles querying the common blazegraph namespace, transforming the result into a format that can be
  * pushed into a target namespace or index, and finally sinks it into the target.
  */
trait CompositeSink extends Sink

/**
  * A sink that queries N-Triples in Blazegraph, transforms them, and pushes the result to the provided sink
  * @param queryGraph
  *   how to query the blazegraph
  * @param transform
  *   function to transform a graph into the format needed by the sink
  * @param sink
  *   function that defines how to sink a chunk of Elem[SinkFormat]
  * @param batchConfig
  *   the batch configuration for the sink
  * @tparam SinkFormat
  *   the type of data accepted by the sink
  */
final class Single[SinkFormat](
    queryGraph: SingleQueryGraph,
    transform: GraphResource => IO[Option[SinkFormat]],
    sink: ElemChunk[SinkFormat] => IO[ElemChunk[Unit]],
    override val batchConfig: BatchConfig,
    retryStrategy: RetryStrategy[Throwable]
) extends CompositeSink {

  override type In = GraphResource
  override def inType: Typeable[GraphResource] = Typeable[GraphResource]

  private def queryTransform: GraphResource => IO[Option[SinkFormat]] = gr =>
    for {
      graph       <- queryGraph(gr).retry(retryStrategy)
      transformed <- graph.flatTraverse(transform)
    } yield transformed

  override def apply(elements: ElemChunk[GraphResource]): IO[ElemChunk[Unit]] =
    elements
      .traverse {
        case e: SuccessElem[GraphResource] => e.evalMapFilter(queryTransform)
        case e: DroppedElem                => IO.pure(e)
        case e: FailedElem                 => IO.pure(e)
      }
      .flatMap(sink)

}

/**
  * A sink that queries N-Triples in Blazegraph for multiple resources, transforms it for each resource, and pushes the
  * result to the provided sink
  * @param queryGraph
  *   how to query the blazegraph
  * @param transform
  *   function to transform a graph into the format needed by the sink
  * @param sink
  *   function that defines how to sink a chunk of Elem[SinkFormat]
  * @param batchConfig
  *   the batch configuration for the sink
  * @tparam SinkFormat
  *   the type of data accepted by the sink
  */
final class Batch[SinkFormat](
    queryGraph: BatchQueryGraph,
    transform: GraphResource => IO[Option[SinkFormat]],
    sink: ElemChunk[SinkFormat] => IO[ElemChunk[Unit]],
    override val batchConfig: BatchConfig,
    retryStrategy: RetryStrategy[Throwable]
)(implicit rcr: RemoteContextResolution)
    extends CompositeSink {

  implicit private val kamonComponent: KamonMetricComponent =
    KamonMetricComponent("batchCompositeSink")

  override type In = GraphResource

  override def inType: Typeable[GraphResource] = Typeable[GraphResource]

  /** Performs the sparql query only using [[SuccessElem]]s from the chunk */
  private def query(elements: ElemChunk[GraphResource]): IO[Option[Graph]] =
    elements.mapFilter(elem => elem.map(_.id).toOption) match {
      case ids if ids.nonEmpty => queryGraph(ids).retry(retryStrategy)
      case _                   => IO.none
    }

  /** Replaces the graph of a provided [[GraphResource]] by extracting its new graph from the provided (full) graph. */
  private def replaceGraph(gr: GraphResource, fullGraph: Graph) = {
    implicit val api: JsonLdApi = TitaniumJsonLdApi.lenient
    fullGraph
      .replaceRootNode(iri"${gr.id}/alias")
      .toCompactedJsonLd(ContextValue.empty)
      .flatMap(_.toGraph)
      .map { g => Option.when(!g.isEmpty)(gr.copy(graph = g.replaceRootNode(gr.id))) }
  }

  override def apply(elements: Chunk[Elem[GraphResource]]): IO[Chunk[Elem[Unit]]] =
    for {
      graph       <- query(elements).span("batchQueryGraph")
      transformed <- graph match {
                       case Some(fullGraph) =>
                         elements.traverse { elem =>
                           elem.evalMapFilter { gr =>
                             replaceGraph(gr, fullGraph).flatMap(_.traverseFilter(transform))
                           }
                         }
                       case None            =>
                         IO.pure(elements.map(_.drop))
                     }
      sank        <- sink(transformed)
    } yield sank
}

object CompositeSink {

  private val logger = Logger[CompositeSink]

  /**
    * @param sparqlClient
    *   client used to connect to the SPARQL store
    * @param namespace
    *   name of the target SPARQL namespace
    * @param common
    *   name of the common SPARQL namespace
    * @param batchConfig
    *   batch configuration for the sink
    * @param sinkConfig
    *   type of sink
    * @return
    *   a function that given a sparql view returns a composite sink that has the view as target
    */
  def sparqlSink(
      sparqlClient: SparqlClient,
      namespace: String,
      common: String,
      batchConfig: BatchConfig,
      sinkConfig: SinkConfig,
      retryStrategy: RetryStrategyConfig
  )(implicit baseUri: BaseUri, rcr: RemoteContextResolution): SparqlProjection => CompositeSink = { target =>
    compositeSink(
      sparqlClient,
      common,
      target.query,
      GraphResourceToNTriples.graphToNTriples,
      SparqlSink(sparqlClient, retryStrategy, batchConfig, namespace).apply,
      batchConfig,
      sinkConfig,
      retryStrategy
    )
  }

  /**
    * @param sparqlClient
    *   SPARQL client used to query the common space
    * @param esClient
    *   client used to push to elasticsearch
    * @param index
    *   name of the target elasticsearch index
    * @param common
    *   name of the common blazegraph namespace
    * @param batchConfig
    *   batch configuration for the sink
    * @param sinkConfig
    *   type of sink
    * @return
    *   a function that given a elasticsearch view returns a composite sink that has the view as target
    */
  def elasticSink(
      sparqlClient: SparqlClient,
      esClient: ElasticSearchClient,
      index: IndexLabel,
      common: String,
      batchConfig: BatchConfig,
      sinkConfig: SinkConfig,
      retryStrategyConfig: RetryStrategyConfig
  )(implicit rcr: RemoteContextResolution): ElasticSearchProjection => CompositeSink = { target =>
    implicit val jsonLdOptions: JsonLdOptions = JsonLdOptions.AlwaysEmbed

    val esSink = ElasticSearchSink.states(esClient, batchConfig, index, Refresh.False)

    compositeSink(
      sparqlClient,
      common,
      target.query,
      new GraphResourceToDocument(target.context, target.includeContext).graphToDocument,
      esSink.apply,
      batchConfig,
      sinkConfig,
      retryStrategyConfig
    )
  }

  private def compositeSink[SinkFormat](
      sparqlClient: SparqlClient,
      common: String,
      query: SparqlConstructQuery,
      transform: GraphResource => IO[Option[SinkFormat]],
      sink: ElemChunk[SinkFormat] => IO[ElemChunk[Unit]],
      batchConfig: BatchConfig,
      sinkConfig: SinkConfig,
      retryStrategyConfig: RetryStrategyConfig
  )(implicit rcr: RemoteContextResolution): CompositeSink = {
    val retryStrategy = RetryStrategy[Throwable](
      retryStrategyConfig,
      {
        case _: SparqlQueryError => true
        case e                   => HttpConnectivityError.test(e)
      },
      RetryStrategy.logError(logger, "sinking")(_, _)
    )
    sinkConfig match {
      case SinkConfig.Single =>
        val queryGraph = new SingleQueryGraph(sparqlClient, common, query)
        new Single(queryGraph, transform, sink, batchConfig, retryStrategy)
      case SinkConfig.Batch  =>
        val queryGraph = new BatchQueryGraph(sparqlClient, common, query)
        new Batch(queryGraph, transform, sink, batchConfig, retryStrategy)
    }
  }
}
