package ch.epfl.bluebrain.nexus.delta.plugins.graph.analytics.indexing

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.kamon.KamonMetricComponent
import ch.epfl.bluebrain.nexus.delta.kernel.syntax.kamonSyntax
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.ElasticSearchClient.Refresh
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.{ElasticSearchBulk, ElasticSearchClient, IndexLabel}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing.ElasticSearchSink
import ch.epfl.bluebrain.nexus.delta.plugins.graph.analytics.indexing.GraphAnalyticsResult.{Index, Noop, UpdateByQuery}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.{DroppedElem, FailedElem, SuccessElem}
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Operation.Sink
import fs2.Chunk
import io.circe.JsonObject
import io.circe.literal._
import io.circe.syntax.EncoderOps
import ch.epfl.bluebrain.nexus.delta.kernel.effect.migration._
import shapeless.Typeable

import scala.concurrent.duration.FiniteDuration

/**
  * Sink that pushes the [[GraphAnalyticsResult]] to the given index
  * @param client
  *   the ES client
  * @param chunkSize
  *   the maximum number of documents to be pushed at once
  * @param maxWindow
  *   the maximum window before a document is pushed
  * @param index
  *   the index to push into
  */
final class GraphAnalyticsSink(
    client: ElasticSearchClient,
    override val chunkSize: Int,
    override val maxWindow: FiniteDuration,
    index: IndexLabel
) extends Sink {

  implicit private val kamonComponent: KamonMetricComponent =
    KamonMetricComponent("graph-analytics")

  override type In = GraphAnalyticsResult

  override def inType: Typeable[GraphAnalyticsResult] = Typeable[GraphAnalyticsResult]

  @SuppressWarnings(Array("OptionGet"))
  private def relationshipsQuery(updates: Map[Iri, Set[Iri]]): JsonObject = {
    val terms = updates.map { case (id, _) => id.asJson }.asJson
    json"""
    {
      "query": {
        "bool": {
          "filter": {
            "terms": {
              "references.@id": $terms
            }
          }
        }
      },
      "script": {
        "id": "updateRelationships",
        "params": {
          "updates": $updates
        }
      }
    }
    """.asObject.get
  }

  private def documentId[A](elem: Elem[A]) = elem.id.toString

  override def apply(elements: Chunk[Elem[GraphAnalyticsResult]]): IO[Chunk[Elem[Unit]]] = {
    val result = elements.foldLeft(GraphAnalyticsSink.empty) {
      case (acc, success: SuccessElem[GraphAnalyticsResult]) =>
        success.value match {
          case Noop                     => acc
          case UpdateByQuery(id, types) => acc.update(id, types)
          case g: Index                 =>
            val bulkAction = ElasticSearchBulk.Index(index, documentId(success), g.asJson)
            acc.add(bulkAction).update(g.id, g.types)
        }
      //TODO: handle correctly the deletion of individual resources when the feature is implemented
      case (acc, _: DroppedElem)                             => acc
      case (acc, _: FailedElem)                              => acc
    }

    client.bulk(result.bulk, Refresh.True).toCatsIO.map(ElasticSearchSink.markElems(_, elements, documentId)) <*
      client.updateByQuery(relationshipsQuery(result.updates), Set(index.value)).toCatsIO
  }.span("graphAnalyticsSink")
}

object GraphAnalyticsSink {

  private val empty = Acc(List.empty, Map.empty)

  // Accumulator of operations to push to Elasticsearch
  final private case class Acc(bulk: List[ElasticSearchBulk], updates: Map[Iri, Set[Iri]]) {
    def add(index: ElasticSearchBulk): Acc    = copy(bulk = index :: bulk)
    def update(id: Iri, types: Set[Iri]): Acc = copy(updates = updates + (id -> types))
  }

}
