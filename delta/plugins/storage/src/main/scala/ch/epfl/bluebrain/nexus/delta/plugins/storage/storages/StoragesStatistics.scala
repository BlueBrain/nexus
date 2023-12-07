package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages

import akka.http.scaladsl.model.Uri.Query
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.EventMetricsProjection.eventMetricsIndex
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.ElasticSearchClient
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.{Storage, StorageStatEntry}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegment
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import io.circe.literal._
import io.circe.{DecodingFailure, JsonObject}

trait StoragesStatistics {

  /**
    * Retrieve the current statistics for a given storage in the given project
    */
  def get(idSegment: IdSegment, project: ProjectRef): IO[StorageStatEntry]

  /**
    * Retrieve remaining space on a storage if it has a capacity.
    */
  final def getStorageAvailableSpace(storage: Storage): IO[Option[Long]] =
    storage.storageValue.capacity.fold(IO.none[Long]) { capacity =>
      get(storage.id, storage.project)
        .redeem(
          _ => Some(capacity),
          stat => Some(capacity - stat.spaceUsed)
        )
    }
}

object StoragesStatistics {

  /**
    * @param client
    *   the Elasticsearch client
    * @param fetchStorageId
    *   the function to fetch the storage ID
    * @param indexPrefix
    *   the index prefix
    * @return
    *   StorageStatistics instance
    */
  def apply(
      client: ElasticSearchClient,
      fetchStorageId: (IdSegment, ProjectRef) => IO[Iri],
      indexPrefix: String
  ): StoragesStatistics = {
    val search = (jsonObject: JsonObject) =>
      client.search(jsonObject, Set(eventMetricsIndex(indexPrefix).value), Query.Empty)()

    (idSegment: IdSegment, project: ProjectRef) => {
      for {
        storageId <- fetchStorageId(idSegment, project)
        query     <- storageStatisticsQuery(project, storageId)
        result    <- search(query)
        stats     <- IO.fromEither(result.as[StorageStatEntry])
      } yield stats
    }
  }

  /**
    * @param projectRef
    *   the project on which the statistics should be computed
    * @param storageId
    *   the ID of the storage on which the statistics should be computed
    * @return
    *   a query for the total number of files and the total size of a storage in a given project
    */
  private def storageStatisticsQuery(projectRef: ProjectRef, storageId: Iri): IO[JsonObject] =
    IO.fromOption(json"""
         {
          "query": {
            "bool": {
              "filter": [
                { "term": { "@type.short": "File" } },
                { "term": { "project": $projectRef } },
                { "term": { "storage": $storageId } }
              ]
            }
          },
          "aggs": {
            "storageSize": { "sum": { "field": "bytes" } },
            "filesCount": { "sum": { "field": "newFileWritten" } }
          },
          "size": 0
        }
        """.asObject)(DecodingFailure("Failed to decode ES statistics query.", List.empty))

}
