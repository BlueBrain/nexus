package ch.epfl.bluebrain.nexus.delta.plugins.storage.files.mocks

import cats.data.NonEmptyList
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileAttributes
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.Storage.RemoteDiskStorage
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.RemoteDiskStorageCopyFiles
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.client.model.RemoteDiskCopyDetails

object RemoteCopyMock {

  def unimplemented: RemoteDiskStorageCopyFiles = withMockedCopy((_, _) => IO(???))

  def withMockedCopy(
      copyMock: (RemoteDiskStorage, NonEmptyList[RemoteDiskCopyDetails]) => IO[NonEmptyList[FileAttributes]]
  ): RemoteDiskStorageCopyFiles = copyMock(_, _)

}
