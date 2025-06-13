package ai.senscience.nexus.delta.plugins.graph.analytics.indexing

import ai.senscience.nexus.delta.plugins.graph.analytics.indexing.GraphAnalyticsResult.{Index, Noop, UpdateByQuery}
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.kamon.KamonMetricComponent
import ch.epfl.bluebrain.nexus.delta.kernel.syntax.kamonSyntax
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.{ElasticSearchAction, ElasticSearchClient, IndexLabel, Refresh}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing.MarkElems
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.{DroppedElem, FailedElem, SuccessElem}
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Operation.Sink
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.config.BatchConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.{Elem, ElemChunk}
import io.circe.JsonObject
import io.circe.literal.*
import io.circe.syntax.EncoderOps
import shapeless.Typeable

/**
  * Sink that pushes the [[GraphAnalyticsResult]] to the given index
  * @param client
  *   the ES client
  * @param batchConfig
  *   the batch configuration for the sink
  * @param index
  *   the index to push into
  */
final class GraphAnalyticsSink(
    client: ElasticSearchClient,
    override val batchConfig: BatchConfig,
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

  override def apply(elements: ElemChunk[GraphAnalyticsResult]): IO[ElemChunk[Unit]] = {
    val result = elements.foldLeft(GraphAnalyticsSink.empty) {
      case (acc, success: SuccessElem[GraphAnalyticsResult]) =>
        success.value match {
          case Noop                     => acc
          case UpdateByQuery(id, types) => acc.update(id, types)
          case g: Index                 =>
            val bulkAction = ElasticSearchAction.Index(index, documentId(success), None, g.asJson)
            acc.add(bulkAction).update(g.id, g.types)
        }
      // TODO: handle correctly the deletion of individual resources when the feature is implemented
      case (acc, _: DroppedElem)                             => acc
      case (acc, _: FailedElem)                              => acc
    }

    client.bulk(result.bulk, Refresh.True).map(MarkElems(_, elements, documentId)) <*
      client.updateByQuery(relationshipsQuery(result.updates), Set(index.value))
  }.span("graphAnalyticsSink")
}

object GraphAnalyticsSink {

  private val empty = Acc(List.empty, Map.empty)

  // Accumulator of operations to push to Elasticsearch
  final private case class Acc(bulk: List[ElasticSearchAction], updates: Map[Iri, Set[Iri]]) {
    def add(index: ElasticSearchAction): Acc  = copy(bulk = index :: bulk)
    def update(id: Iri, types: Set[Iri]): Acc = copy(updates = updates + (id -> types))
  }

}
