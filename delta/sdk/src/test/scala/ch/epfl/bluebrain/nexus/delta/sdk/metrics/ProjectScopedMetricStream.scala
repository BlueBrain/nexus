package ch.epfl.bluebrain.nexus.delta.sdk.metrics

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.sdk.model.metrics.EventMetric.ProjectScopedMetric
import ch.epfl.bluebrain.nexus.delta.sourcing.model.EntityType
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem
import fs2.Stream

object ProjectScopedMetricStream {

  def apply(entityType: EntityType, metrics: ProjectScopedMetric*): Stream[IO, Elem.SuccessElem[ProjectScopedMetric]] =
    Stream.emits(
      metrics.zipWithIndex.map { case (metric, index) =>
        elem(entityType, metric, index + 1L)
      }
    )

  private def elem(entityType: EntityType, metric: ProjectScopedMetric, offset: Long) =
    Elem.SuccessElem(entityType, metric.id, metric.project, metric.instant, Offset.At(offset), metric, metric.rev)

}
