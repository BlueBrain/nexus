package ch.epfl.bluebrain.nexus.delta.sdk.cache

import akka.actor.typed.ActorSystem
import monix.bio.UIO

/**
  * Cache based on composite key.
  *
  * @param underlying
  *   the underlying [[KeyValueStore]]
  */
final class CompositeKeyValueStore[K1, K2, V] private (
    underlying: KeyValueStore[(K1, K2), V]
) {

  /**
    * Fetches values for the provided first-level key.
    */
  def get(key1: K1): UIO[Map[K2, V]] = underlying.entries.map(_.collect {
    case ((k1, k2), v) if k1 == key1 => k2 -> v
  })

  /**
    * Removes the ''key1'' from the cache
    */
  def remove(key1: K1): UIO[Unit] =
    get(key1).flatMap(entries => UIO.traverse(entries.keys)(k2 => underlying.remove((key1, k2)))).void

  /**
    * Fetches values for the composite key
    */
  def get(key1: K1, key2: K2): UIO[Option[V]] = underlying.get((key1, key2))

  /**
    * Add or update the value for the provided composite key
    */
  def put(key1: K1, key2: K2, value: V): UIO[Unit] =
    underlying.put((key1, key2), value)

  /**
    * Adds the passed map to the store, replacing the current key and values values if they already exists.
    */
  def putAll(values: Map[K1, Map[K2, V]]): UIO[Unit] = {
    val flattened = values.toList.flatMap { case (key1, kv) =>
      kv.toList.map { case (key2, value) =>
        ((key1, key2), value)
      }
    }.toMap
    underlying.putAll(flattened)
  }

  /**
    * @return
    *   all the entries in the store
    */
  def entries: UIO[Map[K1, Map[K2, V]]] =
    underlying.entries.map(_.foldLeft(Map.empty[K1, Map[K2, V]]) { case (acc, ((key1, key2), kv)) =>
      acc.updatedWith(key1) {
        case Some(existing) => Some(existing + (key2 -> kv))
        case None           => Some(Map(key2 -> kv))
      }
    })

  /**
    * Fetches values for the provided first-level key.
    */
  def values(key1: K1): UIO[Vector[V]] = underlying.entries.map(_.filter {
    case ((k1, _), _) if k1 == key1 => true
    case _                          => false
  }.values.toVector)

  /**
    * Returns all entries of the cache
    */
  def values: UIO[Vector[V]] = underlying.values

  /**
    * Find a value on the second level entry
    *
    * @param key1
    *   select a specific entry on the first level cache
    * @param f
    *   function to filter the element on the second level cache to be selected
    */
  def find(key1: K1, f: V => Boolean): UIO[Option[V]] =
    underlying.collectFirst {
      case ((k1, _), v) if k1 == key1 && f(v) => v
    }
}

object CompositeKeyValueStore {

  def apply[K1, K2, V](baseName: String, clock: (Long, V) => Long)(implicit
      as: ActorSystem[Nothing],
      config: KeyValueStoreConfig
  ) =
    new CompositeKeyValueStore[K1, K2, V](KeyValueStore.distributed[(K1, K2), V](baseName, clock))

}
