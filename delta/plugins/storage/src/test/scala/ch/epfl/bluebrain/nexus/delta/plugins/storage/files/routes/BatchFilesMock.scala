package ch.epfl.bluebrain.nexus.delta.plugins.storage.files.routes

import cats.data.NonEmptyList
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.FileResource
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.batch.BatchFiles
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.{CopyFileDestination, FileId}
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegment
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.User
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag

import scala.collection.mutable.ListBuffer

object BatchFilesMock {

  def unimplemented: BatchFiles = withMockedCopyFiles((_, _) => _ => IO(???))

  def withStubbedCopyFiles(
      stubbed: NonEmptyList[FileResource],
      buffer: ListBuffer[BatchFilesCopyFilesCalled]
  ): BatchFiles =
    withMockedCopyFiles((source, dest) =>
      c => IO(buffer.addOne(BatchFilesCopyFilesCalled(source, dest, c))).as(stubbed)
    )

  def withMockedCopyFiles(
      copyFilesMock: (CopyFileSource, CopyFileDestination) => Caller => IO[NonEmptyList[FileResource]]
  ): BatchFiles = new BatchFiles {
    override def copyFiles(source: CopyFileSource, dest: CopyFileDestination)(implicit
        c: Caller
    ): IO[NonEmptyList[FileResource]] =
      copyFilesMock(source, dest)(c)
  }

  final case class BatchFilesCopyFilesCalled(source: CopyFileSource, dest: CopyFileDestination, caller: Caller)

  object BatchFilesCopyFilesCalled {
    def fromTestData(
        destProj: ProjectRef,
        sourceProj: ProjectRef,
        sourceFiles: NonEmptyList[FileId],
        user: User,
        destStorage: Option[IdSegment] = None,
        destTag: Option[UserTag] = None
    ): BatchFilesCopyFilesCalled = {
      val expectedCopyFileSource      = CopyFileSource(sourceProj, sourceFiles)
      val expectedCopyFileDestination = CopyFileDestination(destProj, destStorage, destTag)
      BatchFilesCopyFilesCalled(expectedCopyFileSource, expectedCopyFileDestination, Caller(user, Set(user)))
    }
  }

}
