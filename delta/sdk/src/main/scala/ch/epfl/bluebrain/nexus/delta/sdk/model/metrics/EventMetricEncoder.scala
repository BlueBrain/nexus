package ch.epfl.bluebrain.nexus.delta.sdk.model.metrics

import ch.epfl.bluebrain.nexus.delta.sdk.model.metrics.EventMetric.ProjectScopedMetric
import ch.epfl.bluebrain.nexus.delta.sourcing.event.Event
import ch.epfl.bluebrain.nexus.delta.sourcing.event.Event.ScopedEvent

/**
  * Typeclass of Events [[E]] that can be encoded into EventMetric [[M]]
  */
sealed trait EventMetricEncoder[E <: Event, M <: EventMetric] {
  def toMetric(event: E): M
}

/**
 * Typeclass of ScopedEvents [[E]] that can be encoded into ProjectScopedMetric
 */
trait ScopedEventMetricEncoder[E <: ScopedEvent] extends EventMetricEncoder[E, ProjectScopedMetric]
