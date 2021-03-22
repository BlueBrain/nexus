package ch.epfl.bluebrain.nexus.delta.sdk

import ch.epfl.bluebrain.nexus.delta.sdk.EventExchange.EventExchangeValue
import ch.epfl.bluebrain.nexus.delta.sdk.ReferenceExchange.ReferenceExchangeValue
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Event, TagLabel}
import monix.bio.UIO

/**
  * Contract definition for registering and consuming the ability to retrieve the latest resources in a common JSON-LD
  * format from events.
  *
  * Plugins can provide implementations for custom resource types such that those events can be handled in an
  * uniform way.
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
    * Exchange an event for the JSON-LD encoded event.
    *
    * @param event the event to exchange
    * @return some value if the event is defined for this instance, none otherwise
    */
  def toJsonLdEvent(event: Event): Option[JsonLdValue.Aux[E]]

  /**
    * Exchange an event for the latest resource in common formats.
    *
    * @param event the event to exchange
    * @param tag   an optional tag for the resource that will be used for collecting a specific resource revision
    * @return some value if the event is defined for this instance, none otherwise
    */
  def toResource(event: Event, tag: Option[TagLabel]): UIO[Option[EventExchangeValue[A, M]]]
}

object EventExchange {
  final case class EventExchangeValue[A, M](value: ReferenceExchangeValue[A], metadata: JsonLdValue.Aux[M])
}
