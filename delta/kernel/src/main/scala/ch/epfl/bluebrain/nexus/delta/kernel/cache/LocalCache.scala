package ch.epfl.bluebrain.nexus.delta.kernel.cache

import cats.effect.IO
import com.github.benmanes.caffeine.cache.{Cache, Caffeine}

import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.jdk.DurationConverters.*

/**
  * An arbitrary key value store.
  *
  * @tparam K
  *   the key type
  * @tparam V
  *   the value type
  */
trait LocalCache[K, V] {

  /**
    * Adds the (key, value) to the store, replacing the current value if the key already exists.
    *
    * @param key
    *   the key under which the value is stored
    * @param value
    *   the value stored
    */
  def put(key: K, value: V): IO[Unit]

  /**
    * Deletes a key from the store.
    *
    * @param key
    *   the key to be deleted from the store
    */
  def remove(key: K): IO[Unit]

  /**
    * @return
    *   all the entries in the store
    */
  def entries: IO[Map[K, V]]

  /**
    * @return
    *   a vector of all the values in the store
    */
  def values: IO[Vector[V]] = entries.map(_.values.toVector)

  /**
    * @param key
    *   the key
    * @return
    *   an optional value for the provided key
    */
  def get(key: K): IO[Option[V]]

  /**
    * Fetch the value for the given key and if not, compute the new value, insert it in the store and return it This
    * operation is not atomic.
    * @param key
    *   the key
    * @param op
    *   the computation yielding the value to associate with `key`, if `key` is previously unbound.
    */
  def getOrElseUpdate(key: K, op: => IO[V]): IO[V] =
    get(key).flatMap {
      case Some(value) => IO.pure(value)
      case None        =>
        op.flatMap { newValue =>
          put(key, newValue).as(newValue)
        }
    }

  /**
    * Fetch the value for the given key and if not, compute the new value, insert it in the store if defined and return
    * it This operation is not atomic.
    * @param key
    *   the key
    * @param op
    *   the computation yielding the value to associate with `key`, if `key` is previously unbound.
    */
  def getOrElseAttemptUpdate(key: K, op: => IO[Option[V]]): IO[Option[V]] =
    get(key).flatMap {
      case Some(value) => IO.pure(Some(value))
      case None        =>
        op.flatMap {
          case Some(newValue) => put(key, newValue).as(Some(newValue))
          case None           => IO.none
        }
    }

  /**
    * Tests whether the cache contains the given key.
    * @param key
    *   the key to be tested
    */
  def containsKey(key: K): IO[Boolean] = get(key).map(_.isDefined)

}

object LocalCache {

  /**
    * Constructs a local key-value store
    */
  final def apply[K, V](): IO[LocalCache[K, V]] =
    IO.delay {
      val cache: Cache[K, V] =
        Caffeine
          .newBuilder()
          .build[K, V]()
      new LocalCacheImpl(cache)
    }

  /**
    * Constructs a local key-value store following a LRU policy
    *
    * @param config
    *   the cache configuration
    */
  final def lru[K, V](config: CacheConfig): IO[LocalCache[K, V]] =
    lru(config.maxSize.toLong, config.expireAfter)

  /**
    * Constructs a local key-value store following a LRU policy
    *
    * @param maxSize
    *   the max number of entries
    * @param expireAfterAccess
    *   Entries will be removed one the givenduration has elapsed after the entry's creation, the most recent
    *   replacement of its value, or its last access.
    */
  final def lru[K, V](maxSize: Long, expireAfterAccess: FiniteDuration = 1.hour): IO[LocalCache[K, V]] =
    IO.delay {
      val cache: Cache[K, V] =
        Caffeine
          .newBuilder()
          .expireAfterAccess(expireAfterAccess.toJava)
          .maximumSize(maxSize)
          .build[K, V]()
      new LocalCacheImpl(cache)
    }

  /**
    * Constructs a local key-value store
    *
    * @param config
    *   the cache configuration
    */
  final def apply[K, V](config: CacheConfig): IO[LocalCache[K, V]] =
    apply(config.maxSize.toLong, config.expireAfter)

  /**
    * Constructs a local key-value store
    * @param maxSize
    *   the max number of entries
    * @param expireAfterWrite
    *   Entries will be removed one the givenduration has elapsed after the entry's creation or the most recent
    *   replacement of its value.
    */
  final def apply[K, V](maxSize: Long, expireAfterWrite: FiniteDuration = 1.hour): IO[LocalCache[K, V]] =
    IO.delay {
      val cache: Cache[K, V] =
        Caffeine
          .newBuilder()
          .expireAfterWrite(expireAfterWrite.toJava)
          .maximumSize(maxSize)
          .build[K, V]()
      new LocalCacheImpl(cache)
    }

  private class LocalCacheImpl[K, V](cache: Cache[K, V]) extends LocalCache[K, V] {

    override def put(key: K, value: V): IO[Unit] = IO.delay(cache.put(key, value))

    override def get(key: K): IO[Option[V]] = IO.delay(Option(cache.getIfPresent(key)))

    override def remove(key: K): IO[Unit] = IO.delay(cache.invalidate(key))

    override def entries: IO[Map[K, V]] = IO.delay(cache.asMap().asScala.toMap)
  }
}
