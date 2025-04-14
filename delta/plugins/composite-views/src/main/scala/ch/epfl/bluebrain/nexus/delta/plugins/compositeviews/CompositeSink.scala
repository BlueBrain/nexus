package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews

import cats.effect.IO
import cats.implicits.*
import ch.epfl.bluebrain.nexus.delta.kernel.kamon.KamonMetricComponent
import ch.epfl.bluebrain.nexus.delta.kernel.syntax.kamonSyntax
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.SparqlClient
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.indexing.{GraphResourceToNTriples, SparqlSink}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.config.CompositeViewsConfig.SinkConfig
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.config.CompositeViewsConfig.SinkConfig.SinkConfig
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.indexing.{BatchQueryGraph, SingleQueryGraph}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewProjection.{ElasticSearchProjection, SparqlProjection}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.ElasticSearchClient.Refresh
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.{ElasticSearchClient, IndexLabel}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing.{ElasticSearchSink, GraphResourceToDocument}
import ch.epfl.bluebrain.nexus.delta.rdf.graph.Graph
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.{JsonLdApi, JsonLdOptions, TitaniumJsonLdApi}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.query.SparqlQuery.SparqlConstructQuery
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.iriStringContextSyntax
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sourcing.config.BatchConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.state.GraphResource
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.{DroppedElem, FailedElem, SuccessElem}
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Operation.Sink
import fs2.Chunk
import shapeless.Typeable

import scala.concurrent.duration.FiniteDuration

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
  * @param chunkSize
  *   the maximum number of elements to be pushed into the sink
  * @param maxWindow
  *   the maximum time to wait for the chunk to gather [[chunkSize]] elements
  * @tparam SinkFormat
  *   the type of data accepted by the sink
  */
final class Single[SinkFormat](
    queryGraph: SingleQueryGraph,
    transform: GraphResource => IO[Option[SinkFormat]],
    sink: Chunk[Elem[SinkFormat]] => IO[Chunk[Elem[Unit]]],
    override val chunkSize: Int,
    override val maxWindow: FiniteDuration
) extends CompositeSink {

  override type In = GraphResource
  override def inType: Typeable[GraphResource] = Typeable[GraphResource]

  private def queryTransform: GraphResource => IO[Option[SinkFormat]] = gr =>
    for {
      graph       <- queryGraph(gr)
      transformed <- graph.flatTraverse(transform)
    } yield transformed

  override def apply(elements: Chunk[Elem[GraphResource]]): IO[Chunk[Elem[Unit]]] =
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
  * @param chunkSize
  *   the maximum number of elements to be pushed into the sink
  * @param maxWindow
  *   the maximum time to wait for the chunk to gather [[chunkSize]] elements
  * @tparam SinkFormat
  *   the type of data accepted by the sink
  */
final class Batch[SinkFormat](
    queryGraph: BatchQueryGraph,
    transform: GraphResource => IO[Option[SinkFormat]],
    sink: Chunk[Elem[SinkFormat]] => IO[Chunk[Elem[Unit]]],
    override val chunkSize: Int,
    override val maxWindow: FiniteDuration
)(implicit rcr: RemoteContextResolution)
    extends CompositeSink {

  implicit private val kamonComponent: KamonMetricComponent =
    KamonMetricComponent("batchCompositeSink")

  override type In = GraphResource

  override def inType: Typeable[GraphResource] = Typeable[GraphResource]

  /** Performs the sparql query only using [[SuccessElem]]s from the chunk */
  private def query(elements: Chunk[Elem[GraphResource]]): IO[Option[Graph]] =
    elements.mapFilter(elem => elem.map(_.id).toOption) match {
      case ids if ids.nonEmpty => queryGraph(ids)
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
      sinkConfig: SinkConfig
  )(implicit baseUri: BaseUri, rcr: RemoteContextResolution): SparqlProjection => CompositeSink = { target =>
    compositeSink(
      sparqlClient,
      common,
      target.query,
      GraphResourceToNTriples.graphToNTriples,
      SparqlSink(sparqlClient, batchConfig, namespace).apply,
      batchConfig,
      sinkConfig
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
      sinkConfig: SinkConfig
  )(implicit rcr: RemoteContextResolution): ElasticSearchProjection => CompositeSink = { target =>
    implicit val jsonLdOptions: JsonLdOptions = JsonLdOptions.AlwaysEmbed
    val esSink                                =
      ElasticSearchSink.states(
        esClient,
        batchConfig.maxElements,
        batchConfig.maxInterval,
        index,
        Refresh.False
      )
    compositeSink(
      sparqlClient,
      common,
      target.query,
      new GraphResourceToDocument(target.context, target.includeContext).graphToDocument,
      esSink.apply,
      batchConfig,
      sinkConfig
    )
  }

  private def compositeSink[SinkFormat](
      sparqlClient: SparqlClient,
      common: String,
      query: SparqlConstructQuery,
      transform: GraphResource => IO[Option[SinkFormat]],
      sink: Chunk[Elem[SinkFormat]] => IO[Chunk[Elem[Unit]]],
      batchConfig: BatchConfig,
      sinkConfig: SinkConfig
  )(implicit rcr: RemoteContextResolution): CompositeSink = sinkConfig match {
    case SinkConfig.Single =>
      new Single(
        new SingleQueryGraph(sparqlClient, common, query),
        transform,
        sink,
        batchConfig.maxElements,
        batchConfig.maxInterval
      )
    case SinkConfig.Batch  =>
      new Batch(
        new BatchQueryGraph(sparqlClient, common, query),
        transform,
        sink,
        batchConfig.maxElements,
        batchConfig.maxInterval
      )
  }

}
