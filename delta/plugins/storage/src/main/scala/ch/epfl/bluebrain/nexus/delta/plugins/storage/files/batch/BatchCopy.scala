package ch.epfl.bluebrain.nexus.delta.plugins.storage.files.batch

import cats.data.NonEmptyList
import cats.effect.IO
import cats.implicits.toFunctorOps
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.FetchFileResource
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileRejection._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.routes.CopyFileSource
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.Storage.{DiskStorage, RemoteDiskStorage}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.{Storage, StorageType}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageFileRejection.CopyFileRejection
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageFileRejection.CopyFileRejection._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.disk.{DiskCopyDetails, DiskStorageCopyFiles}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.RemoteDiskStorageCopyFiles
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.client.model.RemoteDiskCopyDetails
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.{FetchStorage, StoragesStatistics}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.error.ServiceError.AuthorizationFailed
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import shapeless.syntax.typeable.typeableOps

trait BatchCopy {
  def copyFiles(source: CopyFileSource, destStorage: Storage)(implicit
      c: Caller
  ): IO[NonEmptyList[FileAttributes]]
}

object BatchCopy {
  def mk(
      fetchFile: FetchFileResource,
      fetchStorage: FetchStorage,
      aclCheck: AclCheck,
      storagesStatistics: StoragesStatistics,
      diskCopy: DiskStorageCopyFiles,
      remoteDiskCopy: RemoteDiskStorageCopyFiles
  )(implicit uuidF: UUIDF): BatchCopy = new BatchCopy {

    override def copyFiles(source: CopyFileSource, destStorage: Storage)(implicit
        c: Caller
    ): IO[NonEmptyList[FileAttributes]] =
      destStorage match {
        case disk: Storage.DiskStorage         => copyToDiskStorage(source, disk)
        case remote: Storage.RemoteDiskStorage => copyToRemoteStorage(source, remote)
        case s3: Storage.S3Storage             => unsupported(s3.tpe)
      }

    private def copyToRemoteStorage(source: CopyFileSource, dest: RemoteDiskStorage)(implicit c: Caller) =
      for {
        remoteCopyDetails <- source.files.traverse(fetchRemoteCopyDetails(dest, _))
        _                 <- validateFilesForStorage(dest, remoteCopyDetails.map(_.sourceAttributes.bytes))
        attributes        <- remoteDiskCopy.copyFiles(dest, remoteCopyDetails)
      } yield attributes

    private def copyToDiskStorage(source: CopyFileSource, dest: DiskStorage)(implicit c: Caller) =
      for {
        diskCopyDetails <- source.files.traverse(fetchDiskCopyDetails(dest, _))
        _               <- validateFilesForStorage(dest, diskCopyDetails.map(_.sourceAttributes.bytes))
        attributes      <- diskCopy.copyFiles(dest, diskCopyDetails)
      } yield attributes

    private def validateFilesForStorage(destStorage: Storage, sourcesBytes: NonEmptyList[Long]): IO[Unit] = {
      val maxSize = destStorage.storageValue.maxFileSize
      for {
        _ <- IO.raiseWhen(sourcesBytes.exists(_ > maxSize))(SourceFileTooLarge(maxSize, destStorage.id))
        _ <- validateRemainingStorageCapacity(destStorage, sourcesBytes)
      } yield ()
    }

    private def validateRemainingStorageCapacity(destStorage: Storage, sourcesBytes: NonEmptyList[Long]) =
      for {
        spaceLeft <- storagesStatistics.getStorageAvailableSpace(destStorage)
        totalSize  = sourcesBytes.toList.sum
        _         <- spaceLeft
                       .collectFirst { case s if s < totalSize => notEnoughSpace(totalSize, s, destStorage.id) }
                       .getOrElse(IO.unit)
      } yield ()

    private def fetchDiskCopyDetails(destStorage: DiskStorage, fileId: FileId)(implicit c: Caller) =
      for {
        (file, sourceStorage) <- fetchFileAndValidateStorage(fileId)
        destinationDesc       <- FileDescription(file.attributes.filename, file.attributes.mediaType)
        _                     <- validateDiskStorage(destStorage, sourceStorage)
      } yield DiskCopyDetails(destStorage, destinationDesc, file.attributes)

    private def validateDiskStorage(destStorage: DiskStorage, sourceStorage: Storage) =
      sourceStorage
        .narrowTo[DiskStorage]
        .as(IO.unit)
        .getOrElse(differentStorageTypeError(destStorage, sourceStorage))

    private def fetchRemoteCopyDetails(destStorage: RemoteDiskStorage, fileId: FileId)(implicit c: Caller) =
      for {
        (file, sourceStorage) <- fetchFileAndValidateStorage(fileId)
        destinationDesc       <- FileDescription(file.attributes.filename, file.attributes.mediaType)
        sourceBucket          <- validateRemoteStorage(destStorage, sourceStorage)
      } yield RemoteDiskCopyDetails(destStorage, destinationDesc, sourceBucket, file.attributes)

    private def validateRemoteStorage(destStorage: RemoteDiskStorage, sourceStorage: Storage) =
      sourceStorage
        .narrowTo[RemoteDiskStorage]
        .map(remote => IO.pure(remote.value.folder))
        .getOrElse(differentStorageTypeError(destStorage, sourceStorage))

    private def differentStorageTypeError[A](destStorage: Storage, sourceStorage: Storage) =
      IO.raiseError[A](DifferentStorageTypes(sourceStorage.id, sourceStorage.tpe, destStorage.tpe))

    private def unsupported(tpe: StorageType) = IO.raiseError(CopyFileRejection.UnsupportedOperation(tpe))

    private def notEnoughSpace(totalSize: Long, spaceLeft: Long, destStorage: Iri) =
      IO.raiseError(TotalCopySizeTooLarge(totalSize, spaceLeft, destStorage))

    private def fetchFileAndValidateStorage(id: FileId)(implicit c: Caller) = {
      for {
        file          <- fetchFile.fetch(id)
        sourceStorage <- fetchStorage.fetch(file.value.storage, id.project)
        perm           = sourceStorage.value.storageValue.readPermission
        _             <- aclCheck.authorizeForOr(id.project, perm)(AuthorizationFailed(id.project, perm))
      } yield (file.value, sourceStorage.value)
    }
  }

}
