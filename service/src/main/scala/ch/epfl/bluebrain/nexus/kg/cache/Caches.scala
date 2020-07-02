package ch.epfl.bluebrain.nexus.kg.cache

import ch.epfl.bluebrain.nexus.admin.index.{OrganizationCache, ProjectCache}
import ch.epfl.bluebrain.nexus.kg.archives.ArchiveCache

/**
  * Aggregator of the caches used in the service
  *
 * @param org      the organization cache
  * @param project  the project cache
  * @param view     the view cache
  * @param resolver the resolver cache
  * @param storage  the storage cache
  * @param archive  the archive cache
  * @tparam F the effect type
  */
final class Caches[F[_]](
    val org: OrganizationCache[F],
    val project: ProjectCache[F],
    val view: ViewCache[F],
    val resolver: ResolverCache[F],
    val storage: StorageCache[F],
    val archive: ArchiveCache[F]
)
object Caches {

  /**
    * Constructor for [[Caches]]
    *
    * @param project  the project cache
    * @param view     the view cache
    * @param resolver the resolver cache
    * @tparam F the effect type
    */
  final def apply[F[_]](
      org: OrganizationCache[F],
      project: ProjectCache[F],
      view: ViewCache[F],
      resolver: ResolverCache[F],
      storage: StorageCache[F],
      resourceCollectionCache: ArchiveCache[F]
  ): Caches[F] =
    new Caches(org, project, view, resolver, storage, resourceCollectionCache)

  // $COVERAGE-OFF$
  implicit final def viewCache[F[_]](implicit caches: Caches[F]): ViewCache[F]         = caches.view
  implicit final def storageCache[F[_]](implicit caches: Caches[F]): StorageCache[F]   = caches.storage
  implicit final def projectCache[F[_]](implicit caches: Caches[F]): ProjectCache[F]   = caches.project
  implicit final def resolverCache[F[_]](implicit caches: Caches[F]): ResolverCache[F] = caches.resolver
  implicit final def archiveCache[F[_]](implicit caches: Caches[F]): ArchiveCache[F]   = caches.archive
  // $COVERAGE-ON$
}
