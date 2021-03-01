package ch.epfl.bluebrain.nexus.delta.sdk.eventlog

import ch.epfl.bluebrain.nexus.delta.sdk.model.Event
import com.typesafe.scalalogging.Logger

import scala.collection.concurrent

/**
  * Collection of [[EventExchange]]s.
  */
final class EventExchangeCollection(exchanges: Set[EventExchange]) {

  private val logger: Logger = Logger[EventExchangeCollection]

  private val cache: concurrent.Map[String, EventExchange] = new concurrent.TrieMap

  /**
    * Find an instance of [[EventExchange]] for an [[Event]].
    *
    * @param event  event used to look for [[EventExchange]]
    */
  def findFor(event: Event): Option[EventExchange] = cache.get(event.getClass.getName) match {
    case Some(ex) =>
      Some(ex)
    case None     =>
      exchanges.find(_.appliesTo(event)) match {
        case Some(ex) =>
          cache.putIfAbsent(event.getClass.getName, ex)
          Some(ex)
        case None     =>
          logger.warn(s"Couldn't not find EventExchange for '${event.getClass.getName}'.")
          None

      }

  }
}

object EventExchangeCollection {

  /**
    * Create an instance of [[EventExchangeCollection]]
    */
  def apply(exchanges: Set[EventExchange]): EventExchangeCollection = new EventExchangeCollection(exchanges)
}
