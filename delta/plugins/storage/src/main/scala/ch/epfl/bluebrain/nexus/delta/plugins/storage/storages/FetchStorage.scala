package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages

import cats.effect.IO
import cats.implicits.catsSyntaxMonadError
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileRejection.WrappedStorageRejection
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageRejection.StorageFetchRejection
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegmentRef
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{ProjectRef, ResourceRef}

trait FetchStorage {

  /**
    * Fetch the storage using the ''resourceRef''
    *
    * @param resourceRef
    *   the storage reference (Latest, Revision or Tag)
    * @param project
    *   the project where the storage belongs
    */
  final def fetch[R <: Throwable](
      resourceRef: ResourceRef,
      project: ProjectRef
  ): IO[StorageResource] =
    fetch(IdSegmentRef(resourceRef), project).adaptError { case err: StorageFetchRejection =>
      WrappedStorageRejection(err)
    }

  /**
    * Fetch the last version of a storage
    *
    * @param id
    *   the identifier that will be expanded to the Iri of the storage with its optional rev/tag
    * @param project
    *   the project where the storage belongs
    */
  def fetch(id: IdSegmentRef, project: ProjectRef): IO[StorageResource]

  /**
    * Fetches the default storage for a project.
    *
    * @param project
    *   the project where to look for the default storage
    */
  def fetchDefault(project: ProjectRef): IO[StorageResource]
}
