package ch.epfl.bluebrain.nexus.delta.sdk.cache

import akka.cluster.ddata.LWWMap
import akka.cluster.ddata.typed.scaladsl.Replicator

/**
  * Event used in a [[KeyValueStoreSubscriber]] actor
  */
sealed trait SubscriberCommand

object SubscriberCommand {

  /**
    * Event received when we are notified of a change by the replicator
    */
  final case class SubscribeResponse[K, V](change: Replicator.SubscribeResponse[LWWMap[K, V]]) extends SubscriberCommand

  /**
    * Unsubscribe from the changes and stop the actor
    */
  case object Unsubscribe extends SubscriberCommand

}
