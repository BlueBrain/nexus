package ch.epfl.bluebrain.nexus.delta.plugins.storage.files.mocks

import cats.data.NonEmptyList
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileAttributes
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.Storage.DiskStorage
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.disk.{DiskCopyDetails, DiskStorageCopyFiles}

object DiskCopyMock {

  def unimplemented: DiskStorageCopyFiles = withMockedCopy((_, _) => IO(???))

  def withMockedCopy(
      copyMock: (DiskStorage, NonEmptyList[DiskCopyDetails]) => IO[NonEmptyList[FileAttributes]]
  ): DiskStorageCopyFiles = copyMock(_, _)

}
