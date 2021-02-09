package ch.epfl.bluebrain.nexus.delta.sdk.eventlog

import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.{CompactedJsonLd, ExpandedJsonLd}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Event, ResourceF, TagLabel}
import com.typesafe.scalalogging.Logger
import monix.bio.Task

import scala.reflect.ClassTag

/**
  * Resolver which allows to fetch the latest state for an [[Event]].
  */
abstract class EventExchange {

  type E <: Event

  private val logger: Logger = Logger[EventExchange]

  protected def fetchExpanded(event: E, tag: Option[TagLabel]): Task[Option[ResourceF[ExpandedJsonLd]]]

  protected def fetchCompacted(event: E, tag: Option[TagLabel]): Task[Option[ResourceF[CompactedJsonLd]]]

  protected def cast: ClassTag[E]

  /**
    * Checks if this [[EventExchange]] applies to provided [[Event]]
    * @param event  the event to check
    */
  def appliesTo(event: Event): Boolean = cast.unapply(event).isDefined

  /**
    * Fetches latest [[ExpandedJsonLd]] state for this event.
    *
    * @param event the event for which to fetch the state.
    */
  def toExpanded(event: Event, tag: Option[TagLabel]): Task[Option[ResourceF[ExpandedJsonLd]]] =
    cast.unapply(event) match {
      case Some(ev) => fetchExpanded(ev, tag)
      case None     =>
        Task.delay(logger.warn(s"Event $event could not be resolved.")).as(None)
    }

  /**
    * Fetches latest [[CompactedJsonLd]] state for this event.
    *
    * @param event the event for which to fetch the state.
    */
  def toCompacted(event: Event, tag: Option[TagLabel]): Task[Option[ResourceF[CompactedJsonLd]]] =
    cast.unapply(event) match {
      case Some(ev) => fetchCompacted(ev, tag)
      case None     => Task.delay(logger.warn(s"Event $event could not be resolved.")).as(None)
    }
}
