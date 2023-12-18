package ch.epfl.bluebrain.nexus.delta.plugins.storage.files.mocks

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.{FetchStorage, StorageResource}
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegmentRef
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef

object FetchStorageMock {

  def unimplemented: FetchStorage = withMockedFetch((_, _) => IO(???))

  def withMockedFetch(fetchMock: (IdSegmentRef, ProjectRef) => IO[StorageResource]): FetchStorage = new FetchStorage {
    override def fetch(id: IdSegmentRef, project: ProjectRef): IO[StorageResource] = fetchMock(id, project)
    override def fetchDefault(project: ProjectRef): IO[StorageResource]            = ???
  }

}
