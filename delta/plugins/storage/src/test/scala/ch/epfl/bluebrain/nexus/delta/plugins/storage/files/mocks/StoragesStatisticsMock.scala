package ch.epfl.bluebrain.nexus.delta.plugins.storage.files.mocks

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StoragesStatistics
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageStatEntry
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegment
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef

object StoragesStatisticsMock {

  def unimplemented: StoragesStatistics = withMockedGet((_, _) => IO(???))

  def withMockedGet(getMock: (IdSegment, ProjectRef) => IO[StorageStatEntry]): StoragesStatistics =
    (idSegment: IdSegment, project: ProjectRef) => getMock(idSegment, project)
}
