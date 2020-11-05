package ch.epfl.bluebrain.nexus.delta.sdk.testkit

import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.Lens
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceF
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.Pagination.FromPagination
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.ResultEntry.UnscoredResultEntry
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchParams
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults.UnscoredSearchResults
import ch.epfl.bluebrain.nexus.testkit.IORef
import monix.bio.UIO

/**
  * Cache implementation for dummies
  * @param cache the underlying cache
  */
private[testkit] class ResourceCache[Id, R](cache: IORef[Map[Id, ResourceF[R]]])(implicit discriminator: Lens[R, Id]) {

  /**
    * Fetch a resource by id
    */
  def fetch(id: Id): UIO[Option[ResourceF[R]]] =
    cache.get.map { _.get(id) }

  /**
    * Fetch the first resource satisfying the predicate
    */
  def fetchBy(predicate: R => Boolean): UIO[Option[ResourceF[R]]] =
    cache.get.map {
      _.find { case (_, resource) =>
        predicate(resource.value)
      }.map(_._2)
    }

  /**
    * Lists resources with optional filters.
    * @return
    */
  def list(
      pagination: FromPagination,
      searchParams: SearchParams[R]
  ): UIO[UnscoredSearchResults[ResourceF[R]]] =
    cache.get.map { resources =>
      val filtered = resources.values.filter(searchParams.matches).toVector.sortBy(_.createdAt)
      UnscoredSearchResults(
        filtered.length.toLong,
        filtered.map(UnscoredResultEntry(_)).slice(pagination.from, pagination.from + pagination.size)
      )
    }

  /**
    * Return raw values
    */
  def values: UIO[Set[ResourceF[R]]] = cache.get.map { _.values.toSet }

  /**
    * Put the given value to cache
    * @param resource
    */
  def setToCache(resource: ResourceF[R]): UIO[ResourceF[R]] =
    cache.update(_ + (discriminator.get(resource.value) -> resource)).as(resource)
}

object ResourceCache {

  /**
    * Create a resource cache
    */
  def apply[Id, R](implicit lens: Lens[R, Id]): UIO[ResourceCache[Id, R]] =
    IORef.of[Map[Id, ResourceF[R]]](Map.empty).map(new ResourceCache(_))
}
