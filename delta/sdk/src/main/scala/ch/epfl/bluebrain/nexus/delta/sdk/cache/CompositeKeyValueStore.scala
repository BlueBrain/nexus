package ch.epfl.bluebrain.nexus.delta.sdk.cache

import akka.actor.typed.ActorSystem
import cats.implicits._
import monix.bio.UIO
import scala.collection.concurrent.Map

import java.util.concurrent.ConcurrentHashMap
import scala.jdk.CollectionConverters._

/**
  * Cache based on composite keys which distributes the entries on the two levels
  * @param baseName the unique base name for the cache
  * @param clock    a clock function that determines the next timestamp for a provided value
  * @param firstLevelCache the first level cache which distributes the first level of keys
  */
final class CompositeKeyValueStore[K1, K2, V] private (
    baseName: String,
    clock: (Long, V) => Long,
    firstLevelCache: Map[K1, KeyValueStore[K2, V]]
)(implicit as: ActorSystem[Nothing], config: KeyValueStoreConfig) {

  /**
    * Fetches values for the provided first-level key.
    */
  def get(key1: K1): UIO[Vector[V]] = getOrCreate(key1).values

  /**
    * Fetches values for the composite key
    */
  def get(key1: K1, key2: K2): UIO[Option[V]] = getOrCreate(key1).get(key2)

  /**
    * Add or update the value for the provided composite key
    */
  def put(key1: K1, key2: K2, value: V): UIO[Unit] =
    getOrCreate(key1).put(key2, value)

  /**
    * Returns all entries of the cache
    */
  def values: UIO[Vector[V]] = UIO.pure(firstLevelCache.values.toVector).flatMap(_.flatTraverse(_.values))

  private def getOrCreate(key1: K1): KeyValueStore[K2, V] =
    firstLevelCache.getOrElse(
      key1, {
        val keyValueStore = KeyValueStore.distributed[K2, V](s"$baseName-$key1", clock)
        firstLevelCache.put(key1, keyValueStore)
        keyValueStore
      }
    )
}

object CompositeKeyValueStore {

  def apply[K1, K2, V](baseName: String, clock: (Long, V) => Long)(implicit
      as: ActorSystem[Nothing],
      config: KeyValueStoreConfig
  ) =
    new CompositeKeyValueStore[K1, K2, V](baseName, clock, new ConcurrentHashMap[K1, KeyValueStore[K2, V]]().asScala)

}
