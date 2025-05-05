package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.metrics

import cats.effect.IO
import cats.syntax.all.*
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing.MarkElems
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.metrics.EventMetricsSink.empty
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.model.metrics.EventMetric.ProjectScopedMetric
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.{DroppedElem, FailedElem, SuccessElem}
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Operation.Sink
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.config.BatchConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.{Elem, ElemChunk}
import shapeless.Typeable

final class EventMetricsSink(eventMetrics: EventMetrics, override val batchConfig: BatchConfig) extends Sink {

  private def documentId(elem: Elem[ProjectScopedMetric]) = s"${elem.project}/${elem.id}:${elem.rev}"

  override def apply(elements: ElemChunk[ProjectScopedMetric]): IO[ElemChunk[Unit]] = {
    val result = elements.foldLeft(empty) {
      case (acc, success: SuccessElem[ProjectScopedMetric]) =>
        acc.copy(bulk = acc.bulk :+ success.value)
      case (acc, dropped: DroppedElem)                      =>
        acc.copy(deletes = acc.deletes :+ dropped.project -> dropped.id)
      case (acc, _: FailedElem)                             => acc
    }

    eventMetrics.index(result.bulk).map(MarkElems(_, elements, documentId)) <*
      result.deletes.traverse { case (project, id) =>
        eventMetrics.deleteByResource(project, id)
      }
  }

  /**
    * The underlying element type accepted by the Operation.
    */
  override type In = ProjectScopedMetric

  /**
    * @return
    *   the Typeable instance for the accepted element type
    */
  override def inType: Typeable[ProjectScopedMetric] = Typeable[ProjectScopedMetric]
}

object EventMetricsSink {
  private val empty = Acc(Vector.empty, Vector.empty)

  // Accumulator of operations to push to Elasticsearch
  final private case class Acc(bulk: Vector[ProjectScopedMetric], deletes: Vector[(ProjectRef, Iri)])
}
