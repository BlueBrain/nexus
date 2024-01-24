package ch.epfl.bluebrain.nexus.delta.plugins.storage.files.batch

import cats.data.NonEmptyList
import cats.effect.IO
import cats.implicits.toFunctorOps
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.batch.BatchFilesSuite._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.generators.FileGen
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.mocks.BatchCopyMock
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileCommand.CreateFile
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileRejection.CopyRejection
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.{FileAttributes, FileCommand, FileRejection}
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
import org.scalatest.OptionValues

import java.util.UUID
import scala.collection.mutable.ListBuffer

class BatchFilesSuite
    extends NexusSuite
    with StorageFixtures
    with Generators
    with FileFixtures
    with FileGen
    with OptionValues {

  private val destProj: Project             = genProject()
  private val (destStorageRef, destStorage) = (genRevision(), genStorage(destProj.ref, diskVal))
  private val destFileUUId                  = UUID.randomUUID() // Not testing UUID generation, same for all of them
  private val destination                   = genCopyFileDestination(destProj.ref, destStorage.storage)

  test("batch copying should fetch storage, perform copy and evaluate create file commands") {
    val events                = ListBuffer.empty[Event]
    val fetchFileStorage      = mockFetchFileStorage(destStorageRef, destStorage.storage, events)
    val stubbedDestAttributes = genAttributes()
    val batchCopy             = BatchCopyMock.withStubbedCopyFiles(events, stubbedDestAttributes)

    val sourceProj             = genProject()
    val batchFiles: BatchFiles = mkBatchFiles(events, destProj, sourceProj, destFileUUId, fetchFileStorage, batchCopy)
    implicit val c: Caller     = Caller(genUser(), Set())
    val source                 = genCopyFileSource(sourceProj.ref)

    batchFiles.copyFiles(source, destination).map { obtained =>
      val expectedCommands     = createCommandsFromFileAttributes(stubbedDestAttributes, source.files)
      val expectedResources    = expectedCommands.map(genFileResourceFromCmd)
      val expectedCommandCalls = expectedCommands.toList.map(FileCommandEvaluated)
      val expectedEvents       = activeStorageFetchedAndBatchCopyCalled(source) ++ expectedCommandCalls

      assertEquals(obtained, expectedResources)
      assertEquals(events.toList, expectedEvents)
    }
  }

  test("batch copying should return source file iris including tags and revisions") {
    val events                             = ListBuffer.empty[Event]
    val fetchFileStorage                   = mockFetchFileStorage(destStorageRef, destStorage.storage, events)
    val sourceProj                         = genProject()
    val (byRevFile, byTagFile, latestFile) =
      (genResourceRefWithRev(), genResourceRefWithTag(), genResourceRef())
    val source                             = CopyFileSource(sourceProj.ref, NonEmptyList.of(byTagFile, byRevFile, latestFile))
    val attr                               = source.files.as(attributes())
    val batchCopy                          = BatchCopyMock.withStubbedCopyFiles(events, attr)

    val batchFiles: BatchFiles = mkBatchFiles(events, destProj, sourceProj, destFileUUId, fetchFileStorage, batchCopy)
    implicit val c: Caller     = Caller(genUser(), Set())

    batchFiles.copyFiles(source, destination).map { obtained =>
      val expectedSourceFiles = NonEmptyList.of(byTagFile, byRevFile, latestFile)

      assertEquals(obtained.map(_.value.sourceFile.value), expectedSourceFiles)
    }
  }

  test("copy rejections should be mapped to a file rejection") {
    val events           = ListBuffer.empty[Event]
    val fetchFileStorage = mockFetchFileStorage(destStorageRef, destStorage.storage, events)
    val error            = TotalCopySizeTooLarge(1L, 2L, genIri())
    val batchCopy        = BatchCopyMock.withError(error, events)

    val batchFiles: BatchFiles =
      mkBatchFiles(events, destProj, genProject(), UUID.randomUUID(), fetchFileStorage, batchCopy)
    implicit val c: Caller     = Caller(genUser(), Set())
    val source                 = genCopyFileSource()
    val expectedError          = CopyRejection(source.project, destProj.ref, destStorage.id, error)

    batchFiles.copyFiles(source, destination).interceptEquals(expectedError).accepted

    assertEquals(events.toList, activeStorageFetchedAndBatchCopyCalled(source))
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
      sourceProj: Project,
      fixedUuid: UUID,
      fetchFileStorage: FetchFileStorage,
      batchCopy: BatchCopy
  ): BatchFiles = {
    implicit val uuidF: UUIDF                       = UUIDF.fixed(fixedUuid)
    val evalFileCmd: CreateFile => IO[FileResource] = cmd =>
      IO(events.addOne(FileCommandEvaluated(cmd))).as(genFileResourceFromCmd(cmd))
    val fetchContext: FetchContext[FileRejection]   =
      FetchContextDummy(Map(proj.ref -> proj.context, sourceProj.ref -> sourceProj.context))
        .mapRejection(FileRejection.ProjectContextRejection)
    BatchFiles.mk(fetchFileStorage, fetchContext, evalFileCmd, batchCopy)
  }

  def activeStorageFetchedAndBatchCopyCalled(source: CopyFileSource)(implicit c: Caller): List[Event] = {
    val expectedActiveStorageFetched = ActiveStorageFetched(destination.storage, destProj.ref, destProj.context, c)
    val expectedBatchCopyCalled      = BatchCopyCalled(source, destStorage.storage, c)
    List(expectedActiveStorageFetched, expectedBatchCopyCalled)
  }

  def createCommandsFromFileAttributes(
      stubbedDestAttributes: NonEmptyList[FileAttributes],
      sourceFiles: NonEmptyList[ResourceRef]
  )(implicit
      c: Caller
  ): NonEmptyList[CreateFile] = stubbedDestAttributes.zip(sourceFiles).map { case (destAttr, source) =>
    CreateFile(
      destProj.base.iri / destFileUUId.toString,
      destProj.ref,
      destStorageRef,
      destStorage.value.tpe,
      destAttr,
      c.subject,
      destination.tag,
      Some(source)
    )
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
