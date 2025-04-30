package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.{ElasticSearchClient, IndexLabel}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.nxvFile
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageStatEntry
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegment
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import io.circe.literal.*
import io.circe.{DecodingFailure, JsonObject}
import org.http4s.Query

trait StoragesStatistics {

  /**
    * Retrieve the current statistics for a given storage in the given project
    */
  def get(idSegment: IdSegment, project: ProjectRef): IO[StorageStatEntry]

}

object StoragesStatistics {

  /**
    * @param client
    *   the Elasticsearch client
    * @param fetchStorageId
    *   the function to fetch the storage ID
    * @param index
    *   the index containing the event metrics
    * @return
    *   StorageStatistics instance
    */
  def apply(
      client: ElasticSearchClient,
      fetchStorageId: (IdSegment, ProjectRef) => IO[Iri],
      index: IndexLabel
  ): StoragesStatistics = {
    val search = (jsonObject: JsonObject) => client.search(jsonObject, Set(index.value), Query.empty)()

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
                { "term": { "@type": $nxvFile } },
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
