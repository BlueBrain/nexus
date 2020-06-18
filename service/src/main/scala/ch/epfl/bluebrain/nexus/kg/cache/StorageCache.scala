package ch.epfl.bluebrain.nexus.kg.cache

import java.time.{Clock, Instant}
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

import akka.actor.ActorSystem
import cats.Monad
import cats.effect.{Effect, Timer}
import cats.implicits._
import ch.epfl.bluebrain.nexus.commons.cache.{KeyValueStore, KeyValueStoreConfig}
import ch.epfl.bluebrain.nexus.kg.RevisionedValue
import ch.epfl.bluebrain.nexus.kg.cache.Cache._
import ch.epfl.bluebrain.nexus.kg.cache.StorageProjectCache._
import ch.epfl.bluebrain.nexus.kg.resources.ProjectIdentifier.ProjectRef
import ch.epfl.bluebrain.nexus.kg.storage.Storage
import ch.epfl.bluebrain.nexus.rdf.Iri.AbsoluteIri

class StorageCache[F[_]: Effect: Timer] private (projectToCache: ConcurrentHashMap[UUID, StorageProjectCache[F]])(
    implicit
    as: ActorSystem,
    config: KeyValueStoreConfig,
    clock: Clock
) {

  /**
    * Fetches storages for the provided project.
    *
    * @param ref the project unique reference
    */
  def get(ref: ProjectRef): F[List[Storage]] =
    getOrCreate(ref).get

  /**
    * Fetches storage from the provided project and with the provided id
    *
    * @param ref the project unique reference
    * @param id  the view unique id in the provided project
    */
  def get(ref: ProjectRef, id: AbsoluteIri): F[Option[Storage]] =
    getOrCreate(ref).getBy(id)

  /**
    * Fetches the default storage from the provided project.
    *
    * @param ref the project unique reference
    */
  def getDefault(ref: ProjectRef): F[Option[Storage]] =
    getOrCreate(ref).getDefault

  /**
    * Adds/updates or deprecates a storage on the provided project.
    *
    * @param storage the storage value
    */
  def put(storage: Storage)(implicit instant: Instant = clock.instant()): F[Unit] =
    getOrCreate(storage.ref).put(storage)

  private def getOrCreate(ref: ProjectRef): StorageProjectCache[F] =
    projectToCache.getSafe(ref.id).getOrElse(projectToCache.putAndReturn(ref.id, StorageProjectCache[F](ref)))
}

/**
  * The storage cache backed by a KeyValueStore using akka Distributed Data
  *
  * @param store the underlying Distributed Data LWWMap store.
  */
private class StorageProjectCache[F[_]: Monad] private (store: KeyValueStore[F, AbsoluteIri, RevisionedStorage])
    extends Cache[F, AbsoluteIri, RevisionedStorage](store) {

  implicit private val ordering: Ordering[RevisionedStorage] = Ordering.by((s: RevisionedStorage) => s.rev).reverse

  implicit private def revisioned(storage: Storage)(implicit instant: Instant): RevisionedStorage =
    RevisionedValue(instant.toEpochMilli, storage)

  def get: F[List[Storage]] =
    store.values.map(_.toList.sorted.map(_.value))

  def getDefault: F[Option[Storage]]                            =
    get.map(_.collectFirst { case storage if storage.default => storage })

  def getBy(id: AbsoluteIri): F[Option[Storage]]                =
    get(id).map(_.collectFirst { case RevisionedValue(_, storage) if storage.id == id => storage })

  def put(storage: Storage)(implicit instant: Instant): F[Unit] =
    if (storage.deprecated) store.remove(storage.id)
    else store.put(storage.id, storage)
}

private object StorageProjectCache {

  type RevisionedStorage = RevisionedValue[Storage]

  def apply[F[_]: Effect: Timer](
      project: ProjectRef
  )(implicit as: ActorSystem, config: KeyValueStoreConfig): StorageProjectCache[F] =
    new StorageProjectCache(
      KeyValueStore.distributed(s"storage-${project.id}", (_, storage) => storage.value.rev)
    )

}

object StorageCache {

  def apply[F[_]: Timer: Effect](implicit as: ActorSystem, config: KeyValueStoreConfig, clock: Clock): StorageCache[F] =
    new StorageCache(new ConcurrentHashMap[UUID, StorageProjectCache[F]]())
}
