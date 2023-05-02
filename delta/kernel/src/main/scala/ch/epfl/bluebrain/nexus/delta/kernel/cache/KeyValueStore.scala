package ch.epfl.bluebrain.nexus.delta.kernel.cache

import com.github.benmanes.caffeine.cache.{Cache, Caffeine}
import monix.bio.{IO, UIO}

import scala.concurrent.duration._
import scala.jdk.CollectionConverters._
import scala.jdk.DurationConverters._

/**
  * An arbitrary key value store.
  *
  * @tparam K
  *   the key type
  * @tparam V
  *   the value type
  */
trait KeyValueStore[K, V] {

  /**
    * Adds the (key, value) to the store, replacing the current value if the key already exists.
    *
    * @param key
    *   the key under which the value is stored
    * @param value
    *   the value stored
    */
  def put(key: K, value: V): UIO[Unit]

  /**
    * Adds the passed map to the store, replacing the current key and values values if they already exists.
    */
  def putAll(seq: Map[K, V]): UIO[Unit] =
    IO.traverse(seq) { case (k, v) => put(k, v) } >> UIO.unit

  /**
    * Deletes a key from the store.
    *
    * @param key
    *   the key to be deleted from the store
    */
  def remove(key: K): UIO[Unit]

  /**
    * Deletes the provided keys from the store.
    *
    * @param keys
    *   the key to be deleted from the store
    */
  def removeAll(keys: Set[K]): UIO[Unit]

  /**
    * Adds the (key, value) to the store only if the key does not exists. This operation is not atomic.
    *
    * @param key
    *   the key under which the value is stored
    * @param value
    *   the value stored
    * @return
    *   true if the value was added, false otherwise. The response is wrapped on the effect type ''F[_]''
    */
  def putIfAbsent(key: K, value: V): UIO[Boolean] =
    get(key).flatMap {
      case Some(_) => IO.pure(false)
      case _       => put(key, value).map(_ => true)
    }

  /**
    * If the value for the specified key is present, attempts to compute a new mapping given the key and its current
    * mapped value. This operation is not atomic.
    *
    * @param key
    *   the key under which the value is stored
    * @param f
    *   the function to compute a value
    * @return
    *   None wrapped on the effect type ''F[_]'' if the value does not exist for the given key. Some(value) wrapped on
    *   the effect type ''F[_]'' where value is the result of computing the provided f function on the current value of
    *   the provided key
    */
  def computeIfPresent(key: K, f: V => V): UIO[Option[V]] =
    get(key).flatMap {
      case Some(value) =>
        val computedValue = f(value)
        put(key, computedValue).map(_ => Some(computedValue))
      case other       => IO.pure(other)
    }

  /**
    * @return
    *   all the entries in the store
    */
  def entries: UIO[Map[K, V]]

  /**
    * Notify subscribers of changes now, otherwise they will be notified periodically with the configured
    * `notify-subscribers-interval`.
    */
  def flushChanges: UIO[Unit]

  /**
    * @return
    *   a vector of all the values in the store
    */
  def values: UIO[Vector[V]] =
    entries.map(_.values.toVector)

  /**
    * @return
    *   a set of all the values in the store
    */
  def valuesSet: UIO[Set[V]] =
    entries.map(_.values.toSet)

  /**
    * @param key
    *   the key
    * @return
    *   an optional value for the provided key
    */
  def get(key: K): UIO[Option[V]]

  /**
    * Fetch the value for the given key and if not, compute the new value, insert it in the store and return it This
    * operation is not atomic.
    * @param key
    *   the key
    * @param op
    *   the computation yielding the value to associate with `key`, if `key` is previously unbound.
    */
  def getOrElseUpdate[E](key: K, op: => IO[E, V]): IO[E, V] =
    get(key).flatMap {
      case Some(value) => UIO.pure(value)
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
  def getOrElseAttemptUpdate[E](key: K, op: => IO[E, Option[V]]): IO[E, Option[V]] =
    get(key).flatMap {
      case Some(value) => UIO.some(value)
      case None        =>
        op.flatMap {
          case Some(newValue) => put(key, newValue).as(Some(newValue))
          case None           => UIO.none
        }
    }

  /**
    * @param key
    *   the key
    * @return
    *   an the value for the provided key when found, ''or'' otherwise on the error channel
    */
  def getOr[E](key: K, or: => E): IO[E, V] =
    get(key).flatMap(IO.fromOption(_, or))

  /**
    * Finds the first (key, value) pair that satisfies the predicate.
    *
    * @param f
    *   the predicate to the satisfied
    * @return
    *   the first (key, value) pair that satisfies the predicate or None if none are found
    */
  def find(f: ((K, V)) => Boolean): UIO[Option[(K, V)]]

  /**
    * Finds the first (key, value) pair for which the given partial function is defined, and applies the partial
    * function to it.
    *
    * @param pf
    *   the partial function
    * @return
    *   the first (key, value) pair that satisfies the predicate or None if none are found
    */
  def collectFirst[A](pf: PartialFunction[(K, V), A]): UIO[Option[A]]

  /**
    * Finds the first (key, value) pair for which the given partial function is defined, and applies the partial
    * function to it. If nothing is found, returns on the error channel the passed ''or''.
    *
    * @param pf
    *   the partial function
    */
  def collectFirstOr[A, E](pf: PartialFunction[(K, V), A])(or: => E): IO[E, A] =
    collectFirst(pf).flatMap(IO.fromOption(_, or))

  /**
    * Finds the first value in the store that satisfies the predicate.
    *
    * @param f
    *   the predicate to the satisfied
    * @return
    *   the first value that satisfies the predicate or None if none are found
    */
  def findValue(f: V => Boolean): UIO[Option[V]] =
    entries.map(_.find { case (_, v) => f(v) }.map { case (_, v) => v })

}

object KeyValueStore {

  /**
    * Constructs a local key-value store
    */
  final def apply[K, V](): UIO[KeyValueStore[K, V]] =
    UIO.delay {
      val cache: Cache[K, V] =
        Caffeine
          .newBuilder()
          .build[K, V]()
      new LocalCache(cache)
    }

  /**
    * Constructs a local key-value store following a LRU policy
    *
    * @param config
    *   the cache configuration
    */
  final def localLRU[K, V](config: CacheConfig): UIO[KeyValueStore[K, V]] =
    localLRU(config.maxSize.toLong, config.expireAfter)

  /**
    * Constructs a local key-value store following a LRU policy
    *
    * @param maxSize
    *   the max number of entries
    * @param expireAfterAccess
    *   Entries will be removed one the givenduration has elapsed after the entry's creation, the most recent
    *   replacement of its value, or its last access.
    */
  final def localLRU[K, V](maxSize: Long, expireAfterAccess: FiniteDuration = 1.hour): UIO[KeyValueStore[K, V]] =
    UIO.delay {
      val cache: Cache[K, V] =
        Caffeine
          .newBuilder()
          .expireAfterAccess(expireAfterAccess.toJava)
          .maximumSize(maxSize)
          .build[K, V]()
      new LocalCache(cache)
    }

  /**
    * Constructs a local key-value store
    *
    * @param config
    *   the cache configuration
    */
  final def local[K, V](config: CacheConfig): UIO[KeyValueStore[K, V]] =
    local(config.maxSize.toLong, config.expireAfter)

  /**
    * Constructs a local key-value store
    * @param maxSize
    *   the max number of entries
    * @param expireAfterWrite
    *   Entries will be removed one the givenduration has elapsed after the entry's creation or the most recent
    *   replacement of its value.
    */
  final def local[K, V](maxSize: Long, expireAfterWrite: FiniteDuration = 1.hour): UIO[KeyValueStore[K, V]] =
    UIO.delay {
      val cache: Cache[K, V] =
        Caffeine
          .newBuilder()
          .expireAfterWrite(expireAfterWrite.toJava)
          .maximumSize(maxSize)
          .build[K, V]()
      new LocalCache(cache)
    }

  private class LocalCache[K, V](cache: Cache[K, V]) extends KeyValueStore[K, V] {

    override def put(key: K, value: V): UIO[Unit] = UIO.delay(cache.put(key, value))

    override def get(key: K): UIO[Option[V]] = UIO.delay(Option(cache.getIfPresent(key)))

    override def find(f: ((K, V)) => Boolean): UIO[Option[(K, V)]] = entries.map(_.find(f))

    override def collectFirst[A](pf: PartialFunction[(K, V), A]): UIO[Option[A]] = entries.map(_.collectFirst(pf))

    override def remove(key: K): UIO[Unit] = UIO.delay(cache.invalidate(key))

    override def removeAll(keys: Set[K]): UIO[Unit] = UIO.delay(cache.invalidateAll(keys.asJava))

    override def entries: UIO[Map[K, V]] = UIO.delay(cache.asMap().asScala.toMap)

    override def flushChanges: UIO[Unit] = IO.unit
  }
}
