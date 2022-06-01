package ch.epfl.bluebrain.nexus.delta.sdk

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.EventExchange.EventExchangeResult
import ch.epfl.bluebrain.nexus.delta.sdk.ReferenceExchange.ReferenceExchangeValue
import ch.epfl.bluebrain.nexus.delta.sdk.model.Event
import ch.epfl.bluebrain.nexus.delta.sdk.model.metrics.EventMetric
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import monix.bio.UIO

/**
  * Contract definition for registering and consuming the ability to retrieve the latest resources in a common JSON-LD
  * format from events.
  *
  * Plugins can provide implementations for custom resource types such that those events can be handled in an uniform
  * way.
  *
  * Examples of use-cases would be: the ability to index resources while replaying a log of events of different types,
  * being able to implement SSEs.
  */
trait EventExchange {

  /**
    * The event type.
    */
  type E <: Event

  /**
    * The resource value type.
    */
  type A

  /**
    * The resource metadata value type.
    */
  type M

  /**
    * Exchange an event for the JSON encoded event.
    *
    * @param event
    *   the event to exchange
    * @return
    *   some value if the event is defined for this instance, none otherwise
    */
  def toJsonEvent(event: Event): Option[JsonValue.Aux[E]]

  /**
    * Exchange an event to create the related metric
    * @param event
    *   the event to transform
    * @return
    *   the metric
    */
  def toMetric(event: Event): UIO[Option[EventMetric]]

  /**
    * Exchange an event for the latest resource in common formats.
    *
    * @param event
    *   the event to exchange
    * @param tag
    *   an optional tag for the resource that will be used for collecting a specific resource revision
    * @return
    *   some value if the event is defined for this instance, none otherwise
    */
  def toResource(event: Event, tag: Option[UserTag]): UIO[Option[EventExchangeResult]]
}

object EventExchange {

  /**
    * Result of event exchange
    */
  sealed trait EventExchangeResult {

    /**
      * The id of the resource for which the exchange took place.
      */
    def id: Iri
  }

  /**
    * Representation of event exchange that failed because the resource couldn't be found by a given tag.
    */
  final case class TagNotFound(id: Iri) extends EventExchangeResult

  /**
    * Successful result of [[EventExchange]].
    *
    * @param value
    *   the resource value
    * @param metadata
    *   the resource metadata
    */
  final case class EventExchangeValue[A, M](
      value: ReferenceExchangeValue[A],
      metadata: JsonLdValue.Aux[M]
  ) extends EventExchangeResult {
    override def id: Iri = value.resource.id
  }
}
