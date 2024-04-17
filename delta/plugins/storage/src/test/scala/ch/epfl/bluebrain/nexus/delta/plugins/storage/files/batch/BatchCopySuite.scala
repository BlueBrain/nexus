package ch.epfl.bluebrain.nexus.delta.plugins.storage.files.batch

import cats.data.NonEmptyList
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.batch.BatchCopySuite._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.generators.FileGen
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.mocks._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.{FileDescription, FileMetadata}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.{FileAttributes, FileId, LimitedFileAttributes}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.routes.CopyFileSource
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.{FetchFileResource, FileFixtures, FileResource}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.Storage.{DiskStorage, RemoteDiskStorage, S3Storage}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.{Storage, StorageStatEntry, StorageType}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageFileRejection.CopyFileRejection.{DifferentStorageTypes, SourceFileTooLarge, TotalCopySizeTooLarge, UnsupportedOperation}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.disk.{DiskCopyDetails, DiskStorageCopyFiles}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.RemoteDiskStorageCopyFiles
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.client.model.RemoteDiskCopyDetails
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.acls.{AclCheck, AclSimpleCheck}
import ch.epfl.bluebrain.nexus.delta.sdk.error.ServiceError.AuthorizationFailed
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.{IdSegment, IdSegmentRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.User
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{ProjectRef, ResourceRef}
import ch.epfl.bluebrain.nexus.testkit.Generators
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite

import scala.collection.mutable.ListBuffer

class BatchCopySuite extends NexusSuite with StorageFixtures with Generators with FileFixtures with FileGen {

  private val sourceProj       = genProject()
  private val sourceFileId     = genFileId(sourceProj.ref)
  private val source           = CopyFileSource(sourceProj.ref, NonEmptyList.of(sourceFileId))
  private val storageStatEntry = StorageStatEntry(files = 10L, spaceUsed = 5L)
  private val keywords         = genKeywords()
  private val description      = genString()
  private val name             = genString()
  private val stubbedFileAttr  =
    attributes(genString(), keywords = keywords, description = Some(description), name = Some(name))

  test("successfully perform disk copy") {
    val events                         = ListBuffer.empty[Event]
    val (sourceFileRes, sourceStorage) =
      genFileResourceAndStorage(sourceFileId, sourceProj.context, diskVal, keywords, description, name)
    val (user, aclCheck)               = userAuthorizedOnProjectStorage(sourceStorage.value)

    val batchCopy                = mkBatchCopy(
      fetchFile = stubbedFetchFile(sourceFileRes, events),
      fetchStorage = stubbedFetchStorage(sourceStorage, events),
      aclCheck = aclCheck,
      stats = stubbedStorageStats(storageStatEntry, events),
      diskCopy = stubbedDiskCopy(NonEmptyList.of(stubbedFileAttr), events)
    )
    val destStorage: DiskStorage = genDiskStorage()

    batchCopy.copyFiles(source, destStorage)(caller(user)).map { obtained =>
      val obtainedEvents = events.toList
      assertEquals(obtained, NonEmptyList.of(stubbedFileAttr))
      sourceFileWasFetched(obtainedEvents, sourceFileId)
      sourceStorageWasFetched(obtainedEvents, sourceFileRes.value.storage, sourceProj.ref)
      destinationDiskStorageStatsWereFetched(obtainedEvents, destStorage)
      diskCopyWasPerformed(
        obtainedEvents,
        destStorage,
        sourceFileRes.value.attributes
      )
    }
  }

  test("successfully perform remote disk copy") {
    val events                         = ListBuffer.empty[Event]
    val (sourceFileRes, sourceStorage) =
      genFileResourceAndStorage(sourceFileId, sourceProj.context, remoteVal, keywords, description, name)
    val (user, aclCheck)               = userAuthorizedOnProjectStorage(sourceStorage.value)

    val batchCopy                      = mkBatchCopy(
      fetchFile = stubbedFetchFile(sourceFileRes, events),
      fetchStorage = stubbedFetchStorage(sourceStorage, events),
      aclCheck = aclCheck,
      stats = stubbedStorageStats(storageStatEntry, events),
      remoteCopy = stubbedRemoteCopy(NonEmptyList.of(stubbedFileAttr), events)
    )
    val destStorage: RemoteDiskStorage = genRemoteStorage()

    batchCopy.copyFiles(source, destStorage)(caller(user)).map { obtained =>
      val obtainedEvents = events.toList
      assertEquals(obtained, NonEmptyList.of(stubbedFileAttr))
      sourceFileWasFetched(obtainedEvents, sourceFileId)
      sourceStorageWasFetched(obtainedEvents, sourceFileRes.value.storage, sourceProj.ref)
      destinationRemoteStorageStatsWereNotFetched(obtainedEvents)
      remoteDiskCopyWasPerformed(
        obtainedEvents,
        destStorage,
        sourceFileRes.value.attributes
      )
    }
  }

  test("fail if destination storage is S3") {
    val batchCopy           = mkBatchCopy()
    val (user, destStorage) = (genUser(), genS3Storage())
    val expectedError       = UnsupportedOperation(StorageType.S3Storage)
    batchCopy.copyFiles(source, destStorage)(caller(user)).interceptEquals(expectedError)
  }

  test("fail if a source storage is different to destination storage") {
    val events                         = ListBuffer.empty[Event]
    val (sourceFileRes, sourceStorage) =
      genFileResourceAndStorage(sourceFileId, sourceProj.context, diskVal, keywords, description, name)
    val (user, aclCheck)               = userAuthorizedOnProjectStorage(sourceStorage.value)

    val batchCopy     = mkBatchCopy(
      fetchFile = stubbedFetchFile(sourceFileRes, events),
      fetchStorage = stubbedFetchStorage(sourceStorage, events),
      aclCheck = aclCheck
    )
    val expectedError = DifferentStorageTypes(sourceStorage.id, StorageType.DiskStorage, StorageType.RemoteDiskStorage)

    batchCopy.copyFiles(source, genRemoteStorage())(caller(user)).interceptEquals(expectedError).map { _ =>
      val obtainedEvents = events.toList
      sourceFileWasFetched(obtainedEvents, sourceFileId)
      sourceStorageWasFetched(obtainedEvents, sourceFileRes.value.storage, sourceProj.ref)
    }
  }

  test("fail if user does not have read access on a source file's storage") {
    val events                         = ListBuffer.empty[Event]
    val (sourceFileRes, sourceStorage) =
      genFileResourceAndStorage(sourceFileId, sourceProj.context, diskVal, keywords, description, name)
    val user                           = genUser()
    val aclCheck                       = AclSimpleCheck((user, AclAddress.fromProject(sourceProj.ref), Set())).accepted

    val batchCopy = mkBatchCopy(
      fetchFile = stubbedFetchFile(sourceFileRes, events),
      fetchStorage = stubbedFetchStorage(sourceStorage, events),
      aclCheck = aclCheck
    )

    batchCopy.copyFiles(source, genDiskStorage())(caller(user)).intercept[AuthorizationFailed].map { _ =>
      val obtainedEvents = events.toList
      sourceFileWasFetched(obtainedEvents, sourceFileId)
      sourceStorageWasFetched(obtainedEvents, sourceFileRes.value.storage, sourceProj.ref)
    }
  }

  test("fail if a single source file exceeds max size for destination storage") {
    val events                         = ListBuffer.empty[Event]
    val (sourceFileRes, sourceStorage) =
      genFileResourceAndStorage(sourceFileId, sourceProj.context, diskVal, keywords, description, name, 1000L)
    val (user, aclCheck)               = userAuthorizedOnProjectStorage(sourceStorage.value)

    val batchCopy   = mkBatchCopy(
      fetchFile = stubbedFetchFile(sourceFileRes, events),
      fetchStorage = stubbedFetchStorage(sourceStorage, events),
      aclCheck = aclCheck
    )
    val destStorage = genDiskStorage()
    val error       = SourceFileTooLarge(destStorage.value.maxFileSize, destStorage.id)

    batchCopy.copyFiles(source, destStorage)(caller(user)).interceptEquals(error).map { _ =>
      val obtainedEvents = events.toList
      sourceFileWasFetched(obtainedEvents, sourceFileId)
      sourceStorageWasFetched(obtainedEvents, sourceFileRes.value.storage, sourceProj.ref)
    }
  }

  test("fail if total size of source files is too large for destination disk storage") {
    val events                         = ListBuffer.empty[Event]
    val fileSize                       = 6L
    val capacity                       = 10L
    val statEntry                      = StorageStatEntry(files = 10L, spaceUsed = 1L)
    val spaceLeft                      = capacity - statEntry.spaceUsed
    val (sourceFileRes, sourceStorage) =
      genFileResourceAndStorage(sourceFileId, sourceProj.context, diskVal, keywords, description, name, fileSize)
    val (user, aclCheck)               = userAuthorizedOnProjectStorage(sourceStorage.value)

    val batchCopy = mkBatchCopy(
      fetchFile = stubbedFetchFile(sourceFileRes, events),
      fetchStorage = stubbedFetchStorage(sourceStorage, events),
      aclCheck = aclCheck,
      stats = stubbedStorageStats(statEntry, events)
    )

    val destStorage   = genDiskStorageWithCapacity(capacity)
    val error         = TotalCopySizeTooLarge(fileSize * 2, spaceLeft, destStorage.id)
    val twoFileSource = CopyFileSource(sourceProj.ref, NonEmptyList.of(sourceFileId, sourceFileId))

    batchCopy.copyFiles(twoFileSource, destStorage)(caller(user)).interceptEquals(error).map { _ =>
      val obtainedEvents = events.toList
      sourceFileWasFetched(obtainedEvents, sourceFileId)
      sourceStorageWasFetched(obtainedEvents, sourceFileRes.value.storage, sourceProj.ref)
      destinationDiskStorageStatsWereFetched(obtainedEvents, destStorage)
    }
  }

  private def mkBatchCopy(
      fetchFile: FetchFileResource = FetchFileResourceMock.unimplemented,
      fetchStorage: FetchStorage = FetchStorageMock.unimplemented,
      aclCheck: AclCheck = AclSimpleCheck().accepted,
      stats: StoragesStatistics = StoragesStatisticsMock.unimplemented,
      diskCopy: DiskStorageCopyFiles = DiskCopyMock.unimplemented,
      remoteCopy: RemoteDiskStorageCopyFiles = RemoteCopyMock.unimplemented
  ): BatchCopy = BatchCopy.mk(fetchFile, fetchStorage, aclCheck, stats, diskCopy, remoteCopy)

  private def userAuthorizedOnProjectStorage(storage: Storage): (User, AclCheck) = {
    val user        = genUser()
    val permissions = Set(storage.storageValue.readPermission)
    (user, AclSimpleCheck((user, AclAddress.fromProject(storage.project), permissions)).accepted)
  }

  private def sourceFileWasFetched(events: List[Event], id: FileId) = {
    val obtained = events.collectFirst { case f: FetchFileCalled => f }
    assertEquals(obtained, Some(FetchFileCalled(id)))
  }

  private def sourceStorageWasFetched(events: List[Event], storageRef: ResourceRef.Revision, proj: ProjectRef) = {
    val obtained = events.collectFirst { case f: FetchStorageCalled => f }
    assertEquals(obtained, Some(FetchStorageCalled(IdSegmentRef(storageRef), proj)))
  }

  private def destinationDiskStorageStatsWereFetched(events: List[Event], storage: DiskStorage) = {
    val obtained = events.collectFirst { case f: StoragesStatsCalled => f }
    assertEquals(obtained, Some(StoragesStatsCalled(storage.id, storage.project)))
  }

  private def destinationRemoteStorageStatsWereNotFetched(events: List[Event]) = {
    val obtained = events.collectFirst { case f: StoragesStatsCalled => f }
    assertEquals(obtained, None)
  }

  private def diskCopyWasPerformed(
      events: List[Event],
      storage: DiskStorage,
      sourceAttr: LimitedFileAttributes
  ) = {
    val expectedDiskCopyDetails = DiskCopyDetails(storage, sourceAttr)
    val obtained                = events.collectFirst { case f: DiskCopyCalled => f }
    assertEquals(obtained, Some(DiskCopyCalled(storage, NonEmptyList.of(expectedDiskCopyDetails))))
  }

  private def remoteDiskCopyWasPerformed(
      events: List[Event],
      storage: RemoteDiskStorage,
      sourceAttr: FileAttributes
  ) = {
    val expectedCopyDetails =
      RemoteDiskCopyDetails(
        uuid,
        storage,
        sourceAttr.path,
        storage.value.folder,
        FileMetadata.from(sourceAttr),
        FileDescription.from(sourceAttr)
      )
    val obtained            = events.collectFirst { case f: RemoteCopyCalled => f }
    assertEquals(obtained, Some(RemoteCopyCalled(storage, NonEmptyList.of(expectedCopyDetails))))
  }

  private def caller(user: User): Caller = Caller(user, Set(user))

  private def genDiskStorageWithCapacity(capacity: Long) = {
    val limitedDiskVal = diskVal.copy(capacity = Some(capacity))
    DiskStorage(nxv + genString(), genProject().ref, limitedDiskVal, json"""{"disk": "value"}""")
  }

  private def genDiskStorage() = genDiskStorageWithCapacity(1000L)

  private def genRemoteStorage() =
    RemoteDiskStorage(nxv + genString(), genProject().ref, remoteVal, json"""{"disk": "value"}""")

  private def genS3Storage() =
    S3Storage(nxv + genString(), genProject().ref, s3Val, json"""{"disk": "value"}""")
}

object BatchCopySuite {
  sealed trait Event
  final case class FetchFileCalled(id: FileId)                                    extends Event
  final case class FetchStorageCalled(id: IdSegmentRef, project: ProjectRef)      extends Event
  final case class StoragesStatsCalled(idSegment: IdSegment, project: ProjectRef) extends Event
  final case class DiskCopyCalled(destStorage: Storage.DiskStorage, details: NonEmptyList[DiskCopyDetails])
      extends Event
  final case class RemoteCopyCalled(
      destStorage: Storage.RemoteDiskStorage,
      copyDetails: NonEmptyList[RemoteDiskCopyDetails]
  )                                                                               extends Event

  private def stubbedStorageStats(storageStatEntry: StorageStatEntry, events: ListBuffer[Event]) =
    StoragesStatisticsMock.withMockedGet((id, proj) =>
      addEventAndReturn(events, StoragesStatsCalled(id, proj), storageStatEntry)
    )

  private def stubbedFetchFile(sourceFileRes: FileResource, events: ListBuffer[Event]) =
    FetchFileResourceMock.withMockedFetch(id => addEventAndReturn(events, FetchFileCalled(id), sourceFileRes))

  private def stubbedFetchStorage(storage: StorageResource, events: ListBuffer[Event]) =
    FetchStorageMock.withMockedFetch((id, proj) => addEventAndReturn(events, FetchStorageCalled(id, proj), storage))

  private def stubbedRemoteCopy(stubbedRemoteFileAttr: NonEmptyList[FileAttributes], events: ListBuffer[Event]) =
    RemoteCopyMock.withMockedCopy((storage, details) =>
      addEventAndReturn(events, RemoteCopyCalled(storage, details), stubbedRemoteFileAttr)
    )

  private def stubbedDiskCopy(stubbedDiskFileAttr: NonEmptyList[FileAttributes], events: ListBuffer[Event]) =
    DiskCopyMock.withMockedCopy((storage, details) =>
      addEventAndReturn(events, DiskCopyCalled(storage, details), stubbedDiskFileAttr)
    )

  private def addEventAndReturn[A](events: ListBuffer[Event], event: Event, a: A): IO[A] =
    addEventAndReturnIO(events, event, IO.pure(a))

  private def addEventAndReturnIO[A](events: ListBuffer[Event], event: Event, io: IO[A]): IO[A] =
    IO(events.addOne(event)) >> io

}
