package ch.epfl.bluebrain.nexus.delta.sdk.testkit

import cats.implicits._
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
private[testkit] class ResourceCache[Id, R](cache: IORef[Map[Id, ResourceF[Id, R]]]) {

  /**
    * Fetch a resource by id
    */
  def fetch(id: Id): UIO[Option[ResourceF[Id, R]]] =
    cache.get.map { _.get(id) }

  /**
    * Fetch the first resource satisfying the predicate
    */
  def fetchBy(predicate: R => Boolean): UIO[Option[ResourceF[Id, R]]] =
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
      searchParams: SearchParams[Id, R]
  ): UIO[UnscoredSearchResults[ResourceF[Id, R]]] =
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
  def values: UIO[Set[ResourceF[Id, R]]] = cache.get.map { _.values.toSet }

  /**
    * Put the given value to cache
    * @param resource
    * @return
    */
  def setToCache(resource: ResourceF[Id, R]): UIO[ResourceF[Id, R]] =
    cache.update(_ + (resource.id -> resource)).as(resource)
}

object ResourceCache {

  /**
    * Create a resource cache
    */
  def apply[Id, R]: UIO[ResourceCache[Id, R]] =
    IORef.of[Map[Id, ResourceF[Id, R]]](Map.empty).map(new ResourceCache(_))
}
