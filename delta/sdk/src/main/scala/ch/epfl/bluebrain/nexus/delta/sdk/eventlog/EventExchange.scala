package ch.epfl.bluebrain.nexus.delta.sdk.eventlog

import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.{CompactedJsonLd, ExpandedJsonLd}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Event, ResourceF}
import com.typesafe.scalalogging.Logger
import monix.bio.Task

import scala.reflect.ClassTag

/**
  * Resolver which allows to fetch the latest state for an [[Event]].
  */
trait EventExchange[E <: Event] {

  private val logger: Logger = Logger[EventExchange[E]]

  protected def fetchExpanded(event: E): Task[ResourceF[ExpandedJsonLd]]

  protected def fetchCompacted(event: E): Task[ResourceF[CompactedJsonLd]]

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
  def toExpanded(event: Event): Task[Option[ResourceF[ExpandedJsonLd]]] = cast.unapply(event) match {
    case Some(ev) => fetchExpanded(ev).map(Some(_))
    case None     =>
      Task.delay(logger.warn(s"Event $event could not be resolved.")).as(None)
  }

  /**
    * Fetches latest [[CompactedJsonLd]] state for this event.
    *
    * @param event the event for which to fetch the state.
    */
  def toCompacted(event: Event): Task[Option[ResourceF[CompactedJsonLd]]] = cast.unapply(event) match {
    case Some(ev) => fetchCompacted(ev).map(Some(_))
    case None     => Task.delay(logger.warn(s"Event $event could not be resolved.")).as(None)
  }
}
