package ch.epfl.bluebrain.nexus.delta.plugins.storage.files

import cats.data.NonEmptyList
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.BatchFilesSpec._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.batch.{BatchCopy, BatchFiles}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.generators.FileGen
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileCommand.CreateFile
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.{FileAttributes, FileCommand, FileRejection}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.routes.CopyFileSource
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StorageFixtures
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.Storage
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegment
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.{Project, ProjectContext}
import ch.epfl.bluebrain.nexus.delta.sdk.projects.{FetchContext, FetchContextDummy}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{ProjectRef, ResourceRef}
import ch.epfl.bluebrain.nexus.testkit.Generators
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite

import java.util.UUID
import scala.collection.mutable.ListBuffer

class BatchFilesSpec extends NexusSuite with StorageFixtures with Generators with FileFixtures with FileGen {

  test("batch copying files should fetch storage, perform copy and evaluate create file commands") {
    val events                        = ListBuffer.empty[Event]
    val destProj: Project             = genProject()
    val (destStorageRef, destStorage) = (genRevision(), genStorage(destProj.ref, diskVal))
    val fetchFileStorage              = mockFetchFileStorage(destStorageRef, destStorage.storage, events)
    val stubbedDestAttributes         = genAttributes()
    val batchCopy                     = mockBatchCopy(events, stubbedDestAttributes)
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

  def mockBatchCopy(events: ListBuffer[Event], stubbedAttr: NonEmptyList[FileAttributes]) = new BatchCopy {
    override def copyFiles(source: CopyFileSource, destStorage: Storage)(implicit
        c: Caller
    ): IO[NonEmptyList[FileAttributes]] =
      IO(events.addOne(BatchCopyCalled(source, destStorage, c))).as(stubbedAttr)
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

object BatchFilesSpec {
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
