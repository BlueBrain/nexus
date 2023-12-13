package ch.epfl.bluebrain.nexus.delta.plugins.storage.files.batch

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.batch.BatchFilesSuite._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.generators.FileGen
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.mocks.BatchCopyMock
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileCommand.CreateFile
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileRejection.CopyRejection
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.{FileCommand, FileRejection}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.routes.CopyFileSource
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.{FetchFileStorage, FileFixtures, FileResource}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StorageFixtures
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.Storage
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageFileRejection.CopyFileRejection.TotalCopySizeTooLarge
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegment
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.{Project, ProjectContext}
import ch.epfl.bluebrain.nexus.delta.sdk.projects.{FetchContext, FetchContextDummy}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{ProjectRef, ResourceRef}
import ch.epfl.bluebrain.nexus.testkit.Generators
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite

import java.util.UUID
import scala.collection.mutable.ListBuffer

class BatchFilesSuite extends NexusSuite with StorageFixtures with Generators with FileFixtures with FileGen {

  test("batch copying should fetch storage, perform copy and evaluate create file commands") {
    val events                        = ListBuffer.empty[Event]
    val destProj: Project             = genProject()
    val (destStorageRef, destStorage) = (genRevision(), genStorage(destProj.ref, diskVal))
    val fetchFileStorage              = mockFetchFileStorage(destStorageRef, destStorage.storage, events)
    val stubbedDestAttributes         = genAttributes()
    val batchCopy                     = BatchCopyMock.withStubbedCopyFiles(events, stubbedDestAttributes)
    val destFileUUId                  = UUID.randomUUID() // Not testing UUID generation, same for all of them

    val batchFiles: BatchFiles = mkBatchFiles(events, destProj, destFileUUId, fetchFileStorage, batchCopy)
    implicit val c: Caller     = Caller(genUser(), Set())
    val (source, destination)  = (genCopyFileSource(), genCopyFileDestination(destProj.ref, destStorage.storage))
    val obtained               = batchFiles.copyFiles(source, destination).accepted

    val expectedFileIri = destProj.base.iri / destFileUUId.toString
    val expectedCmds    = stubbedDestAttributes.map(
      CreateFile(expectedFileIri, destProj.ref, destStorageRef, destStorage.value.tpe, _, c.subject, destination.tag)
    )

    // resources returned are based on file command evaluation
    assertEquals(obtained, expectedCmds.map(genFileResourceFromCmd))

    val expectedActiveStorageFetched = ActiveStorageFetched(destination.storage, destProj.ref, destProj.context, c)
    val expectedBatchCopyCalled      = BatchCopyCalled(source, destStorage.storage, c)
    val expectedCommandsEvaluated    = expectedCmds.toList.map(FileCommandEvaluated)
    val expectedEvents               = List(expectedActiveStorageFetched, expectedBatchCopyCalled) ++ expectedCommandsEvaluated
    assertEquals(events.toList, expectedEvents)
  }

  test("copy rejections should be mapped to a file rejection") {
    val events                        = ListBuffer.empty[Event]
    val destProj: Project             = genProject()
    val (destStorageRef, destStorage) = (genRevision(), genStorage(destProj.ref, diskVal))
    val fetchFileStorage              = mockFetchFileStorage(destStorageRef, destStorage.storage, events)
    val error                         = TotalCopySizeTooLarge(1L, 2L, genIri())
    val batchCopy                     = BatchCopyMock.withError(error, events)

    val batchFiles: BatchFiles = mkBatchFiles(events, destProj, UUID.randomUUID(), fetchFileStorage, batchCopy)
    implicit val c: Caller     = Caller(genUser(), Set())
    val sourceProj             = genProject()
    val (source, destination)  =
      (genCopyFileSource(sourceProj.ref), genCopyFileDestination(destProj.ref, destStorage.storage))
    val expectedError          = CopyRejection(sourceProj.ref, destProj.ref, destStorage.id, error)

    batchFiles.copyFiles(source, destination).interceptEquals(expectedError).accepted

    val expectedActiveStorageFetched = ActiveStorageFetched(destination.storage, destProj.ref, destProj.context, c)
    val expectedBatchCopyCalled      = BatchCopyCalled(source, destStorage.storage, c)
    val expectedEvents               = List(expectedActiveStorageFetched, expectedBatchCopyCalled)
    assertEquals(events.toList, expectedEvents)
  }

  def mockFetchFileStorage(
      storageRef: ResourceRef.Revision,
      storage: Storage,
      events: ListBuffer[Event]
  ): FetchFileStorage = new FetchFileStorage {
    override def fetchAndValidateActiveStorage(storageIdOpt: Option[IdSegment], ref: ProjectRef, pc: ProjectContext)(
        implicit caller: Caller
    ): IO[(ResourceRef.Revision, Storage)] =
      IO(events.addOne(ActiveStorageFetched(storageIdOpt, ref, pc, caller))).as(storageRef -> storage)
  }

  def mkBatchFiles(
      events: ListBuffer[Event],
      proj: Project,
      fixedUuid: UUID,
      fetchFileStorage: FetchFileStorage,
      batchCopy: BatchCopy
  ): BatchFiles = {
    implicit val uuidF: UUIDF                       = UUIDF.fixed(fixedUuid)
    val evalFileCmd: CreateFile => IO[FileResource] = cmd =>
      IO(events.addOne(FileCommandEvaluated(cmd))).as(genFileResourceFromCmd(cmd))
    val fetchContext: FetchContext[FileRejection]   =
      FetchContextDummy(Map(proj.ref -> proj.context)).mapRejection(FileRejection.ProjectContextRejection)
    BatchFiles.mk(fetchFileStorage, fetchContext, evalFileCmd, batchCopy)
  }
}

object BatchFilesSuite {
  sealed trait Event
  final case class ActiveStorageFetched(
      storageIdOpt: Option[IdSegment],
      ref: ProjectRef,
      pc: ProjectContext,
      caller: Caller
  )                                                                                              extends Event
  final case class BatchCopyCalled(source: CopyFileSource, destStorage: Storage, caller: Caller) extends Event
  final case class FileCommandEvaluated(cmd: FileCommand)                                        extends Event
}
