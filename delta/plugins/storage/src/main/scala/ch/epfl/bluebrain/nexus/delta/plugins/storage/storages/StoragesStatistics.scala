package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages

import akka.http.scaladsl.model.Uri.Query
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClasspathResourceUtils.ioJsonObjectContentOf
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.EventMetricsProjection
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.ElasticSearchClient
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageRejection.StorageFetchRejection
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageStatsCollection.StorageStatEntry.singleStorageStatResultDecoder
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageStatsCollection.{StorageStatEntry, StoragesStats}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClient.HttpResult
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegment
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import com.typesafe.scalalogging.Logger
import io.circe.{Json, JsonObject}
import monix.bio.{IO, UIO}

trait StoragesStatistics {

  /**
    * Retrieve the current statistics for all storages
    */
  def get(): UIO[StoragesStats]

  /**
    * Retrieve the current statistics for storages in the given project
    */
  def get(project: ProjectRef): UIO[StoragesStats]

  /**
    * Retrieve the current statistics for a given storage in the given project
    */
  def get(idSegment: IdSegment, project: ProjectRef): IO[StorageFetchRejection, StorageStatEntry]

}

object StoragesStatistics {

  implicit private val classLoader: ClassLoader = getClass.getClassLoader
  implicit private val logger: Logger           = Logger[StoragesStatistics]

  private val storagesAggQuery =
    ioJsonObjectContentOf("query/storages-statistics-aggregations.json")
      .logAndDiscardErrors("Storage 'storages-statistics-aggregations.json' template not found")
      .memoizeOnSuccess

  private val storagesAggByProjectQuery =
    ioJsonObjectContentOf(
      "query/storages-statistics-aggregations-by-proj.json",
      "project" -> "{{project}}"
    )
      .logAndDiscardErrors("Storage 'storages-statistics-aggregations-by-proj.json' template not found")
      .memoizeOnSuccess

  private val storagesAggByIdAndProjectQuery =
    ioJsonObjectContentOf(
      "query/storages-statistics-aggregations-by-id-proj.json",
      "project" -> "{{project}}",
      "storage" -> "{{storage}}"
    )
      .logAndDiscardErrors("Storage 'storages-statistics-aggregations-by-id-proj.json' template not found")
      .memoizeOnSuccess

  /**
    * @param projectRef
    *   the project on which to restrict the query
    * @return
    *   an ES query as JsonObject that gets the storages statistics in the given project
    */
  def statsByProjectQuery(projectRef: ProjectRef): UIO[JsonObject]                      =
    storagesAggByProjectQuery
      .map(_.replace("project" -> "{{project}}", projectRef))

  /**
    * @param storageId
    *   the id of the storage on which to restrict the query
    * @param projectRef
    *   the project on which to restrict the query
    * @return
    *   an ES query as JsonObject that gets statistics for the given storage in the given project
    */
  def statsByIdAndProjectQuery(storageId: Iri, projectRef: ProjectRef): UIO[JsonObject] =
    storagesAggByIdAndProjectQuery
      .map(jsonObject =>
        jsonObject
          .replace("project" -> "{{project}}", projectRef)
          .replace("storage" -> "{{storage}}", storageId)
      )

  def apply(client: ElasticSearchClient, storages: Storages): StoragesStatistics =
    apply(
      client.search(_, Set(EventMetricsProjection.eventMetricsIndex.value), Query.Empty)(),
      storages.fetch(_, _).map(_.id)
    )

  def apply(
      search: JsonObject => HttpResult[Json],
      fetchStorageId: (IdSegment, ProjectRef) => IO[StorageFetchRejection, Iri]
  ): StoragesStatistics =
    new StoragesStatistics {
      println(fetchStorageId)

      override def get(): UIO[StoragesStats] = {
        for {
          query  <- storagesAggQuery
          result <- search(query)
          stats  <- IO.fromEither(result.as[StoragesStats])
        } yield stats
      }.hideErrors

      /**
        * Retrieve the current statistics for storages in the given project
        */
      override def get(project: ProjectRef): UIO[StoragesStats] = {
        for {
          query  <- statsByProjectQuery(project)
          result <- search(query)
          stats  <- IO.fromEither(result.as[StoragesStats])
        } yield stats
      }.hideErrors

      /**
        * Retrieve the current statistics for a given storage in the given project
        */
      override def get(idSegment: IdSegment, project: ProjectRef): IO[StorageFetchRejection, StorageStatEntry] = {
        for {
          storageId <- fetchStorageId(idSegment, project)
          query     <- statsByIdAndProjectQuery(storageId, project).hideErrors
          result    <- search(query).hideErrors
          stats     <- IO.fromEither(result.as[StorageStatEntry](singleStorageStatResultDecoder)).hideErrors
        } yield stats
      }
    }

}
