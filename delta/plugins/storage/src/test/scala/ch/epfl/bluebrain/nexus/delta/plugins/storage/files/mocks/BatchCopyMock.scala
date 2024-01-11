package ch.epfl.bluebrain.nexus.delta.plugins.storage.files.mocks

import cats.data.NonEmptyList
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ResourcesSearchParams.FileUserMetadata
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.batch.BatchFilesSuite.{BatchCopyCalled, Event}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.batch.BatchCopy
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileAttributes
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.routes.CopyFileSource
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.Storage
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageFileRejection.CopyFileRejection
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller

import scala.collection.mutable.ListBuffer

object BatchCopyMock {

  def withError(e: CopyFileRejection, events: ListBuffer[Event]): BatchCopy =
    withMockedCopyFiles((source, destStorage) =>
      caller => IO(events.addOne(BatchCopyCalled(source, destStorage, caller))) >> IO.raiseError(e)
    )

  def withStubbedCopyFiles(
      events: ListBuffer[Event],
      stubbedAttr: NonEmptyList[(FileAttributes, Option[FileUserMetadata])]
  ): BatchCopy =
    withMockedCopyFiles((source, destStorage) =>
      caller => IO(events.addOne(BatchCopyCalled(source, destStorage, caller))).as(stubbedAttr)
    )

  def withMockedCopyFiles(
      copyFilesMock: (CopyFileSource, Storage) => Caller => IO[NonEmptyList[(FileAttributes, Option[FileUserMetadata])]]
  ): BatchCopy =
    new BatchCopy {
      override def copyFiles(source: CopyFileSource, destStorage: Storage)(implicit
          c: Caller
      ): IO[NonEmptyList[(FileAttributes, Option[FileUserMetadata])]] = copyFilesMock(source, destStorage)(c)
    }

}
