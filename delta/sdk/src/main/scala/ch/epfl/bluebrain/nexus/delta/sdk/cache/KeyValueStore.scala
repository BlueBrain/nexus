package ch.epfl.bluebrain.nexus.delta.sdk.cache

import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.AskPattern._
import akka.cluster.ddata.LWWRegister.Clock
import akka.cluster.ddata.typed.scaladsl.DistributedData
import akka.cluster.ddata.typed.scaladsl.Replicator._
import akka.cluster.ddata.{LWWMap, LWWMapKey, SelfUniqueAddress}
import akka.cluster.typed.Cluster
import akka.util.Timeout
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategy
import ch.epfl.bluebrain.nexus.delta.sdk.cache.KeyValueStoreError.{DistributedDataError, ReadWriteConsistencyTimeout}
import com.typesafe.scalalogging.Logger
import monix.bio.{IO, Task, UIO}
import retry.CatsEffect._
import retry.syntax.all._

import scala.concurrent.duration.FiniteDuration

/**
  * An arbitrary key value store.
  *
  * @tparam K the key type
  * @tparam V the value type
  */
trait KeyValueStore[K, V] {

  /**
    * Adds the (key, value) to the store, replacing the current value if the key already exists.
    *
    * @param key   the key under which the value is stored
    * @param value the value stored
    */
  def put(key: K, value: V): UIO[Unit]

  /**
    * Deletes a key from the store.
    *
    * @param key the key to be deleted from the store
    */
  def remove(key: K): UIO[Unit]

  /**
    * Adds the (key, value) to the store only if the key does not exists.
    *
    * @param key   the key under which the value is stored
    * @param value the value stored
    * @return true if the value was added, false otherwise. The response is wrapped on the effect type ''F[_]''
    */
  def putIfAbsent(key: K, value: V): UIO[Boolean] =
    get(key).flatMap {
      case Some(_) => IO.pure(false)
      case _       => put(key, value).map(_ => true)
    }

  /**
    * If the value for the specified key is present, attempts to compute a new mapping given the key and its current mapped value.
    *
    * @param key the key under which the value is stored
    * @param f   the function to compute a value
    * @return None wrapped on the effect type ''F[_]'' if the value does not exist for the given key.
    *         Some(value) wrapped on the effect type ''F[_]''
    *         where value is the result of computing the provided f function on the current value of the provided key
    */
  def computeIfPresent(key: K, f: V => V): UIO[Option[V]] =
    get(key).flatMap {
      case Some(value) =>
        val computedValue = f(value)
        put(key, computedValue).map(_ => Some(computedValue))
      case other       => IO.pure(other)
    }

  /**
    * @return all the entries in the store
    */
  def entries: UIO[Map[K, V]]

  /**
    * Notify subscribers of changes now, otherwise they will be notified periodically
    * with the configured `notify-subscribers-interval`.
    */
  def flushChanges: UIO[Unit]

  /**
    * @return a vector of all the values in the store
    */
  def values: UIO[Vector[V]] =
    entries.map(_.values.toVector)

  /**
    * @return a set of all the values in the store
    */
  def valuesSet: UIO[Set[V]] =
    entries.map(_.values.toSet)

  /**
    * @param key the key
    * @return an optional value for the provided key
    */
  def get(key: K): UIO[Option[V]] =
    entries.map(_.get(key))

  /**
    * @param key the key
    * @return an the value for the provided key when found, ''or'' otherwise on the error channel
    */
  def getOr[E](key: K, or: => E): IO[E, V] =
    get(key).flatMap(IO.fromOption(_, or))

  /**
    * Finds the first (key, value) pair that satisfies the predicate.
    *
    * @param f the predicate to the satisfied
    * @return the first (key, value) pair that satisfies the predicate or None if none are found
    */
  def find(f: (K, V) => Boolean): UIO[Option[(K, V)]]                 =
    entries.map(_.find { case (k, v) => f(k, v) })

  /**
    * Finds the first (key, value) pair  for which the given partial function is defined,
    * and applies the partial function to it.
    *
    * @param pf the partial function
    * @return the first (key, value) pair that satisfies the predicate or None if none are found
    */
  def collectFirst[A](pf: PartialFunction[(K, V), A]): UIO[Option[A]] =
    entries.map(_.collectFirst(pf))

  /**
    * Finds the first (key, value) pair  for which the given partial function is defined,
    * and applies the partial function to it. If nothing is found, returns on the error channel the passed ''or''.
    *
    * @param pf the partial function
    */
  def collectFirstOr[A, E](pf: PartialFunction[(K, V), A])(or: => E): IO[E, A] =
    collectFirst(pf).flatMap(IO.fromOption(_, or))

  /**
    * Finds the first value in the store that satisfies the predicate.
    *
    * @param f the predicate to the satisfied
    * @return the first value that satisfies the predicate or None if none are found
    */
  def findValue(f: V => Boolean): UIO[Option[V]] =
    entries.map(_.find { case (_, v) => f(v) }.map { case (_, v) => v })

}

object KeyValueStore {

  private val log = Logger[KeyValueStore.type]

  private val worthRetryingOnWriteErrors: Throwable => Boolean = {
    case _: ReadWriteConsistencyTimeout | _: DistributedDataError => true
    case _                                                        => false
  }

  /**
    * Constructs a key value store backed by Akka Distributed Data with WriteAll and ReadLocal consistency
    * configuration. The store is backed by a LWWMap.
    *
    * @param id              the ddata key
    * @param clock           a clock function that determines the next timestamp for a provided value
    * @param as              the implicitly underlying actor system
    * @param config          the key value store configuration
    * @tparam K the key type
    * @tparam V the value type
    */
  final def distributed[K, V](
      id: String,
      clock: (Long, V) => Long
  )(implicit as: ActorSystem[Nothing], config: KeyValueStoreConfig): KeyValueStore[K, V] = {
    val retryStrategy = RetryStrategy[Throwable](
      config.retry,
      worthRetryingOnWriteErrors,
      (err, details) =>
        Task {
          log.warn(s"Retrying on cache with id '$id' on retry details '$details'", err)
        }
    )
    new DDataKeyValueStore[K, V](id, clock, retryStrategy, config.askTimeout, config.consistencyTimeout)
  }

  private class DDataKeyValueStore[K, V](
      id: String,
      clock: (Long, V) => Long,
      retryStrategy: RetryStrategy[Throwable],
      askTimeout: FiniteDuration,
      consistencyTimeout: FiniteDuration
  )(implicit as: ActorSystem[Nothing])
      extends KeyValueStore[K, V] {

    import retryStrategy._

    implicit private val node: Cluster           = Cluster(as)
    private val uniqueAddr: SelfUniqueAddress    = SelfUniqueAddress(node.selfMember.uniqueAddress)
    implicit private val registerClock: Clock[V] = (currentTimestamp: Long, value: V) => clock(currentTimestamp, value)
    implicit private val timeout: Timeout        = Timeout(askTimeout)

    private val replicator              = DistributedData(as).replicator
    private val mapKey                  = LWWMapKey[K, V](id)
    private val consistencyTimeoutError = ReadWriteConsistencyTimeout(consistencyTimeout)
    private val distributeWriteError    = DistributedDataError("The update couldn't be performed")
    private val dataDeletedError        = DistributedDataError(
      "The update couldn't be performed because the entry has been deleted"
    )

    override def put(key: K, value: V): UIO[Unit] = {
      val msg =
        Update(mapKey, LWWMap.empty[K, V], WriteAll(consistencyTimeout))(_.put(uniqueAddr, key, value, registerClock))
      IO.deferFuture(replicator ? msg)
        .flatMap {
          case _: UpdateSuccess[_]     => IO.unit
          // $COVERAGE-OFF$
          case _: UpdateTimeout[_]     => IO.raiseError(consistencyTimeoutError)
          case _: UpdateFailure[_]     => IO.raiseError(distributeWriteError)
          case _: UpdateDataDeleted[_] => IO.raiseError(dataDeletedError)
          // $COVERAGE-ON$
        }
        .retryingOnSomeErrors(retryWhen)
        .hideErrors
    }

    override def remove(key: K): UIO[Unit] = {
      val msg = Update(mapKey, LWWMap.empty[K, V], WriteAll(consistencyTimeout))(_.remove(uniqueAddr, key))
      IO.deferFuture(replicator ? msg)
        .flatMap {
          case _: UpdateSuccess[_]     => IO.unit
          // $COVERAGE-OFF$
          case _: UpdateTimeout[_]     => IO.raiseError(consistencyTimeoutError)
          case _: UpdateFailure[_]     => IO.raiseError(distributeWriteError)
          case _: UpdateDataDeleted[_] => IO.raiseError(dataDeletedError)
          // $COVERAGE-ON$
        }
        .retryingOnSomeErrors(retryWhen)
        .hideErrors
    }

    override def entries: UIO[Map[K, V]] = {
      val msg = Get(mapKey, ReadLocal)
      IO.deferFuture(replicator ? msg)
        .flatMap {
          case g @ GetSuccess(`mapKey`) => IO.pure(g.get(mapKey).entries)
          case _: NotFound[_]           => IO.pure(Map.empty[K, V])
          // $COVERAGE-OFF$
          case _: GetFailure[_]         => IO.raiseError(consistencyTimeoutError)
          // $COVERAGE-ON$
        }
        .retryingOnSomeErrors(retryWhen)
        .hideErrors
    }

    override def flushChanges: UIO[Unit] = IO.pure(replicator ! FlushChanges)
  }
}
