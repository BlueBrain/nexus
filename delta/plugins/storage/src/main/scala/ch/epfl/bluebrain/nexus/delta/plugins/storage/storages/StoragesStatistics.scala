package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages

import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageRejection.StorageFetchRejection
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageStatsCollection
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageStatsCollection.StorageStatEntry
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegment
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import monix.bio.{IO, UIO}

trait StoragesStatistics {

  /**
    * Retrieve the current statistics for all storages
    */
  def get(): UIO[StorageStatsCollection]

  /**
    * Retrieve the current statistics for storages in the given project
    */
  def get(project: ProjectRef): UIO[Map[Iri, StorageStatEntry]]

  /**
    * Retrieve the current statistics for the given project
    */
  def get(idSegment: IdSegment, project: ProjectRef): IO[StorageFetchRejection, StorageStatEntry]

  /**
    * Remove the statistics for the given project
    */
  def remove(project: ProjectRef): UIO[Unit]

}
