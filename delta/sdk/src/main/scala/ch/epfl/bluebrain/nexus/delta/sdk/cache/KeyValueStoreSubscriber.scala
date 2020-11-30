package ch.epfl.bluebrain.nexus.delta.sdk.cache

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.cluster.ddata.typed.scaladsl.{DistributedData, Replicator}
import akka.cluster.ddata.{LWWMap, LWWMapKey}
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.cache.KeyValueStoreSubscriber.KeyValueStoreChange.{ValueAdded, ValueModified, ValueRemoved}
import ch.epfl.bluebrain.nexus.delta.sdk.cache.KeyValueStoreSubscriber.KeyValueStoreChanges
import ch.epfl.bluebrain.nexus.delta.sdk.cache.SubscriberCommand.{SubscribeResponse, Unsubscribe}
import monix.bio.{IO, UIO}
import monix.execution.Scheduler

/**
  * Used with a [[KeyValueStoreSubscriber]] actor to trigger an
  * action when a change on a [[KeyValueStore]] occurs
  */
trait OnKeyValueStoreChange[K, V] {

  /**
    * Method that gets triggered when a change to key value store occurs.
    *
    * @param value the changes made
    */
  def apply(value: KeyValueStoreChanges[K, V]): UIO[Unit]
}

object OnKeyValueStoreChange {

  /**
    * Nothing to compute when a change to the key value store ocurrs.
    *
    * @tparam K the key type
    * @tparam V the value type
    */
  def noEffect[K, V]: OnKeyValueStoreChange[K, V] =
    (_: KeyValueStoreChanges[K, V]) => IO.unit

  /**
    * Apply the given option for every type of event
    */
  def apply[K, V](
      onCreate: (K, V) => UIO[Unit],
      onUpdate: (K, V) => UIO[Unit],
      onRemove: (K, V) => UIO[Unit]
  ): OnKeyValueStoreChange[K, V] =
    (value: KeyValueStoreChanges[K, V]) =>
      value.values.toList match {
        case Nil    => IO.unit
        case values =>
          values
            .traverse {
              case ValueAdded(k, v)    => onCreate(k, v)
              case ValueModified(k, v) => onUpdate(k, v)
              case ValueRemoved(k, v)  => onRemove(k, v)
            }
            .as(())
      }
}

object KeyValueStoreSubscriber {

  /**
    * Enumeration of types related to changes to the key value store.
    */
  sealed trait KeyValueStoreChange[K, V] extends Product with Serializable
  object KeyValueStoreChange {

    /**
      * Signals that an element has been added to the key value store.
      *
      * @param key   the key
      * @param value the value
      */
    final case class ValueAdded[K, V](key: K, value: V) extends KeyValueStoreChange[K, V]

    /**
      * Signals that an already existing element has been updated from the key value store.
      *
      * @param key   the key
      * @param value the value
      */
    final case class ValueModified[K, V](key: K, value: V) extends KeyValueStoreChange[K, V]

    /**
      * Signals that an already existing element has been removed from the key value store.
      *
      * @param key   the key
      * @param value the value
      */
    final case class ValueRemoved[K, V](key: K, value: V) extends KeyValueStoreChange[K, V]
  }

  /**
    * The set of changes that have occurred on the primary store
    *
    * @param values the set of changes
    */
  final case class KeyValueStoreChanges[K, V](values: Set[KeyValueStoreChange[K, V]])

  /**
    * Constructs the [[KeyValueStoreSubscriber]] actor that receives messages
    * from the key value store whenever a change occurs.
    *
    * @param cacheId  id of the cache to subscribe to
    * @param onChange the method that gets triggered whenever a change to the key value store occurs
    * @tparam K the key type
    * @tparam V the value type
    */
  final def apply[K, V](cacheId: String, onChange: OnKeyValueStoreChange[K, V])(implicit
      scheduler: Scheduler
  ): Behavior[SubscriberCommand] =
    Behaviors.setup { context =>
      DistributedData.withReplicatorMessageAdapter[SubscriberCommand, LWWMap[K, V]] { replicator =>
        val key = LWWMapKey[K, V](cacheId)
        replicator.subscribe(key, SubscribeResponse.apply)

        def run(previous: Map[K, V]): Behavior[SubscriberCommand] =
          Behaviors.receiveMessage[SubscriberCommand] {
            case Unsubscribe                                      =>
              replicator.unsubscribe(key)
              Behaviors.stopped
            case SubscribeResponse(change: Replicator.Changed[_]) =>
              def diff(recent: Map[K, V]): KeyValueStoreChanges[K, V] = {
                val added   = (recent -- previous.keySet).map { case (k, v) => ValueAdded(k, v) }.toSet
                val removed = (previous -- recent.keySet).map { case (k, v) => ValueRemoved(k, v) }.toSet

                val modified = (recent -- added.map(_.key)).foldLeft(Set.empty[KeyValueStoreChange[K, V]]) {
                  case (acc, (k, v)) =>
                    previous.get(k).filter(_ == v) match {
                      case None => acc + ValueModified(k, v)
                      case _    => acc
                    }
                }
                KeyValueStoreChanges(added ++ modified ++ removed)
              }
              val recent  = change.get(key).entries
              val changes = diff(recent)
              if (changes.values.nonEmpty)
                onChange(changes).runAsyncAndForget
              context.log.debug("Received a Changed message from the key value store. Values changed: '{}'", changes)
              run(recent)
            case other                                            =>
              context.log.error("Skipping received a message different from Changed. Message: '{}'", other)
              Behaviors.unhandled
          }

        run(Map.empty[K, V])
      }
    }
}
