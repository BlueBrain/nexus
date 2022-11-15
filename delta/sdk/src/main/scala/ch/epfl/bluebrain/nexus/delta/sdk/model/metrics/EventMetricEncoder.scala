package ch.epfl.bluebrain.nexus.delta.sdk.model.metrics

import ch.epfl.bluebrain.nexus.delta.sdk.model.metrics.EventMetric.ProjectScopedMetric
import ch.epfl.bluebrain.nexus.delta.sourcing.event.Event
import ch.epfl.bluebrain.nexus.delta.sourcing.event.Event.ScopedEvent
import ch.epfl.bluebrain.nexus.delta.sourcing.model.EntityType
import io.circe.Decoder

/**
  * Typeclass of Events [[E]] that can be encoded into EventMetric [[M]]
  */
sealed trait EventMetricEncoder[E <: Event, M <: EventMetric] {
  def databaseDecoder: Decoder[E]

  def entityType: EntityType

  def eventToMetric: E => M

  def toMetric: Decoder[M] =
    databaseDecoder.map(eventToMetric)
}

/**
  * Typeclass of ScopedEvents [[E]] that can be encoded into ProjectScopedMetric
  */
trait ScopedEventMetricEncoder[E <: ScopedEvent] extends EventMetricEncoder[E, ProjectScopedMetric]
