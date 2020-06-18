package ch.epfl.bluebrain.nexus.kg.cache

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

import akka.actor.ActorSystem
import cats.Monad
import cats.effect.{Effect, Timer}
import cats.implicits._
import ch.epfl.bluebrain.nexus.commons.cache.{KeyValueStore, KeyValueStoreConfig}
import ch.epfl.bluebrain.nexus.kg.cache.Cache._
import ch.epfl.bluebrain.nexus.kg.resolve.Resolver
import ch.epfl.bluebrain.nexus.kg.resources.ProjectIdentifier.ProjectRef
import ch.epfl.bluebrain.nexus.rdf.Iri.AbsoluteIri

class ResolverCache[F[_]: Effect: Timer] private (projectToCache: ConcurrentHashMap[UUID, ResolverProjectCache[F]])(
    implicit
    as: ActorSystem,
    config: KeyValueStoreConfig
) {

  /**
    * Fetches resolvers for the provided project.
    *
    * @param ref the project unique reference
    */
  def get(ref: ProjectRef): F[List[Resolver]] =
    getOrCreate(ref).get

  /**
    * Fetches resolver from the provided project and with the provided id
    *
    * @param ref the project unique reference
    * @param id  the view unique id in the provided project
    */
  def get(ref: ProjectRef, id: AbsoluteIri): F[Option[Resolver]] =
    getOrCreate(ref).get(id)

  /**
    * Adds/updates or deprecates a resolver on the provided project.
    *
    * @param resolver the storage value
    */
  def put(resolver: Resolver): F[Unit] =
    getOrCreate(resolver.ref).put(resolver)

  private def getOrCreate(ref: ProjectRef): ResolverProjectCache[F] =
    projectToCache.getSafe(ref.id).getOrElse(projectToCache.putAndReturn(ref.id, ResolverProjectCache[F](ref)))

}

/**
  * The resolver cache for a project backed by a KeyValueStore using akka Distributed Data
  *
  * @param store the underlying Distributed Data LWWMap store.
  */
private class ResolverProjectCache[F[_]: Monad] private (store: KeyValueStore[F, AbsoluteIri, Resolver])
    extends Cache[F, AbsoluteIri, Resolver](store) {

  implicit private val ordering: Ordering[Resolver] = Ordering.by(_.priority)

  def get: F[List[Resolver]] = store.values.map(_.toList.sorted)

  def put(resolver: Resolver): F[Unit] =
    if (resolver.deprecated) store.remove(resolver.id)
    else store.put(resolver.id, resolver)

}

private object ResolverProjectCache {

  def apply[F[_]: Effect: Timer](
      project: ProjectRef
  )(implicit as: ActorSystem, config: KeyValueStoreConfig): ResolverProjectCache[F] =
    new ResolverProjectCache(KeyValueStore.distributed(s"resolver-${project.id}", (_, resolver) => resolver.rev))

}

object ResolverCache {

  def apply[F[_]: Effect: Timer](implicit as: ActorSystem, config: KeyValueStoreConfig): ResolverCache[F] =
    new ResolverCache(new ConcurrentHashMap[UUID, ResolverProjectCache[F]]())
}
