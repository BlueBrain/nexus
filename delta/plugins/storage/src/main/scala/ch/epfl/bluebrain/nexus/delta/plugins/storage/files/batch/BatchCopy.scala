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
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageRejection.DifferentStorageType
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.{Storage, StorageType}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageFileRejection.CopyFileRejection
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageFileRejection.CopyFileRejection.{SourceFileTooLarge, TotalCopySizeTooLarge}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.disk.{DiskCopyDetails, DiskStorageCopyFiles}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.RemoteDiskStorageCopyFiles
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.client.model.RemoteDiskCopyDetails
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.{FetchStorage, StoragesStatistics}
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.error.ServiceError.AuthorizationFailed
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
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
        _                 <- validateSpaceOnStorage(dest, remoteCopyDetails.map(_.sourceAttributes.bytes))
        attributes        <- remoteDiskCopy.copyFiles(dest, remoteCopyDetails)
      } yield attributes

    private def copyToDiskStorage(source: CopyFileSource, dest: DiskStorage)(implicit c: Caller) =
      for {
        diskCopyDetails <- source.files.traverse(fetchDiskCopyDetails(dest, _))
        _               <- validateSpaceOnStorage(dest, diskCopyDetails.map(_.sourceAttributes.bytes))
        attributes      <- diskCopy.copyFiles(dest, diskCopyDetails)
      } yield attributes

    private def validateSpaceOnStorage(destStorage: Storage, sourcesBytes: NonEmptyList[Long]): IO[Unit] = for {
      space    <- storagesStatistics.getStorageAvailableSpace(destStorage)
      maxSize   = destStorage.storageValue.maxFileSize
      _        <- IO.raiseWhen(sourcesBytes.exists(_ > maxSize))(SourceFileTooLarge(maxSize, destStorage.id))
      totalSize = sourcesBytes.toList.sum
      _        <- space
                    .collectFirst {
                      case s if s < totalSize => IO.raiseError(TotalCopySizeTooLarge(totalSize, s, destStorage.id))
                    }
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
        .getOrElse(IO.raiseError(differentStorageTypeError(destStorage, sourceStorage)))

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
        .getOrElse(IO.raiseError[Label](differentStorageTypeError(destStorage, sourceStorage)))

    private def differentStorageTypeError(destStorage: Storage, sourceStorage: Storage) =
      DifferentStorageType(destStorage.id, found = sourceStorage.tpe, expected = destStorage.tpe)

    private def unsupported(tpe: StorageType) = IO.raiseError(CopyFileRejection.UnsupportedOperation(tpe))

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
