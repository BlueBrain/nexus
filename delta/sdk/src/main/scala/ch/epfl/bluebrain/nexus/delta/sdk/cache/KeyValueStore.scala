package ch.epfl.bluebrain.nexus.delta.sdk.cache

import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.AskPattern._
import akka.cluster.ddata.LWWRegister.Clock
import akka.cluster.ddata.typed.scaladsl.DistributedData
import akka.cluster.ddata.typed.scaladsl.Replicator._
import akka.cluster.ddata.{LWWMap, LWWMapKey, LWWRegister, SelfUniqueAddress}
import akka.cluster.typed.Cluster
import akka.util.Timeout
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategy
import ch.epfl.bluebrain.nexus.delta.sdk.cache.KeyValueStoreError.{DistributedDataError, ReadWriteConsistencyTimeout}
import com.github.benmanes.caffeine.cache.{Cache, Caffeine}
import com.typesafe.scalalogging.Logger
import monix.bio.{IO, Task, UIO}
import retry.syntax.all._

import java.time.Duration
import scala.jdk.CollectionConverters._

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

  implicit private val log: Logger = Logger[KeyValueStore.type]

  private val worthRetryingOnWriteErrors: Throwable => Boolean = {
    case _: ReadWriteConsistencyTimeout | _: DistributedDataError => true
    case _                                                        => false
  }

  /**
    * Constructs a key value store backed by Akka Distributed Data with ReadLocal consistency configuration. The store
    * is backed by a LWWMap.
    *
    * @param id
    *   the ddata key
    * @param clock
    *   a clock function that determines the next timestamp for a provided value
    * @param as
    *   the implicitly underlying actor system
    * @param config
    *   the key value store configuration
    */
  final def distributed[K, V](
      id: String,
      clock: (Long, V) => Long
  )(implicit as: ActorSystem[Nothing], config: KeyValueStoreConfig): KeyValueStore[K, V] = {
    val registerClock: Clock[V] = (currentTimestamp: Long, value: V) => clock(currentTimestamp, value)
    new DDataKeyValueStore[K, V](id, registerClock)
  }

  /**
    * Constructs a key value store backed by Akka Distributed Data with ReadLocal consistency configuration. It relies
    * on the default clock so it means that either there are synchronized clocks or we have only one writer (e. g. a
    * Cluster Singleton) The store is backed by a LWWMap.
    *
    * @param id
    *   the ddata key
    * @param as
    *   the implicitly underlying actor system
    * @param config
    *   the key value store configuration
    */
  final def distributedWithDefaultClock[K, V](
      id: String
  )(implicit as: ActorSystem[Nothing], config: KeyValueStoreConfig): KeyValueStore[K, V] =
    new DDataKeyValueStore[K, V](id, LWWRegister.defaultClock)

  private class DDataKeyValueStore[K, V](
      id: String,
      registerClock: Clock[V]
  )(implicit as: ActorSystem[Nothing], config: KeyValueStoreConfig)
      extends KeyValueStore[K, V] {

    private val retryStrategy = RetryStrategy[Throwable](
      config.retry,
      worthRetryingOnWriteErrors,
      (err, details) =>
        Task {
          log.error(s"Retrying on cache with id '$id' on retry details '$details'", err)
        }
    )

    private val writeConsistency = WriteAll(config.consistencyTimeout)

    implicit private val node: Cluster        = Cluster(as)
    private val uniqueAddr: SelfUniqueAddress = SelfUniqueAddress(node.selfMember.uniqueAddress)
    implicit private val timeout: Timeout     = Timeout(config.askTimeout)

    private val replicator              = DistributedData(as).replicator
    private val mapKey                  = LWWMapKey[K, V](id)
    private val consistencyTimeoutError = ReadWriteConsistencyTimeout(config.consistencyTimeout)
    private val distributeWriteError    = DistributedDataError("The update couldn't be performed")
    private val dataDeletedError        = DistributedDataError(
      "The update couldn't be performed because the entry has been deleted"
    )

    override def put(key: K, value: V): UIO[Unit] = {
      val msg =
        Update(mapKey, LWWMap.empty[K, V], writeConsistency)(_.put(uniqueAddr, key, value, registerClock))
      IO.deferFuture(replicator ? msg)
        .flatMap {
          case _: UpdateSuccess[_]     => IO.unit
          // $COVERAGE-OFF$
          case _: UpdateTimeout[_]     => IO.raiseError(consistencyTimeoutError)
          case _: UpdateFailure[_]     => IO.raiseError(distributeWriteError)
          case _: UpdateDataDeleted[_] => IO.raiseError(dataDeletedError)
          // $COVERAGE-ON$
        }
        .retryingOnSomeErrors(retryStrategy.retryWhen, retryStrategy.policy, retryStrategy.onError)
        .hideErrors
    }

    override def get(key: K): UIO[Option[V]] =
      entries.map(_.get(key))

    override def find(f: ((K, V)) => Boolean): UIO[Option[(K, V)]] =
      entries.map(_.find(f))

    override def collectFirst[A](pf: PartialFunction[(K, V), A]): UIO[Option[A]] =
      entries.map(_.collectFirst(pf))

    override def remove(key: K): UIO[Unit] = removeAll(Set(key))

    override def removeAll(keys: Set[K]): UIO[Unit] = {
      val msg = Update(mapKey, LWWMap.empty[K, V], writeConsistency) { node =>
        keys.foldLeft(node) { case (n, k) => n.remove(uniqueAddr, k) }
      }
      IO.deferFuture(replicator ? msg)
        .flatMap {
          case _: UpdateSuccess[_]     => IO.unit
          // $COVERAGE-OFF$
          case _: UpdateTimeout[_]     => IO.raiseError(consistencyTimeoutError)
          case _: UpdateFailure[_]     => IO.raiseError(distributeWriteError)
          case _: UpdateDataDeleted[_] => IO.raiseError(dataDeletedError)
          // $COVERAGE-ON$
        }
        .retryingOnSomeErrors(retryStrategy.retryWhen, retryStrategy.policy, retryStrategy.onError)
        .hideErrors
    }

    override def entries: UIO[Map[K, V]] = {
      val msg = Get(mapKey, ReadLocal)
      IO.deferFuture(replicator ? msg)
        .flatMap {
          case g @ GetSuccess(`mapKey`) => IO.pure(g.get(mapKey).entries)
          case _: GetFailure[_]         => IO.raiseError(consistencyTimeoutError)
          case _                        => IO.pure(Map.empty[K, V])
        }
        .retryingOnSomeErrors(retryStrategy.retryWhen, retryStrategy.policy, retryStrategy.onError)
        .hideErrors
    }

    override def flushChanges: UIO[Unit] = IO.pure(replicator ! FlushChanges)
  }

  /**
    * Constructs a local key-value store following a LRU policy
    *
    * @param maxSize
    *   the max number of entries in the Map
    */
  final def localLRU[K, V](maxSize: Long): UIO[KeyValueStore[K, V]] =
    UIO.delay {
      val cache: Cache[K, V] =
        Caffeine
          .newBuilder()
          .expireAfterAccess(Duration.ofHours(1L)) //TODO make this configurable
          .maximumSize(maxSize)
          .build[K, V]()
      new LocalLruCache(cache)
    }

  private class LocalLruCache[K, V](cache: Cache[K, V]) extends KeyValueStore[K, V] {

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
