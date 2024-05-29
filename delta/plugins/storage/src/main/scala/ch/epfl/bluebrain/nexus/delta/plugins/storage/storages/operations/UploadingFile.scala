package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations

import akka.http.scaladsl.model.{BodyPartEntity, ContentType}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.UploadedFileInformation
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.Storage.{DiskStorage, RemoteDiskStorage, S3Storage}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.{AbsolutePath, DigestAlgorithm, Storage}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageFileRejection.SaveFileRejection
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageFileRejection.SaveFileRejection.FileContentLengthIsMissing
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef}

/**
  * Represents a file being uploaded with one implementing by storage type
  */
sealed trait UploadingFile extends Product with Serializable {

  /**
    * @return
    *   the target project
    */
  def project: ProjectRef

  /**
    * @return
    *   the filename for the file
    */
  def filename: String

  /**
    * @return
    *   the entity representing the file content (may be strict or streaming)
    */
  def entity: BodyPartEntity
}

object UploadingFile {

  final case class DiskUploadingFile(
      project: ProjectRef,
      volume: AbsolutePath,
      algorithm: DigestAlgorithm,
      filename: String,
      entity: BodyPartEntity
  ) extends UploadingFile

  final case class RemoteUploadingFile(project: ProjectRef, folder: Label, filename: String, entity: BodyPartEntity)
      extends UploadingFile

  final case class S3UploadingFile(
      project: ProjectRef,
      bucket: String,
      filename: String,
      contentType: ContentType,
      contentLength: Long,
      entity: BodyPartEntity
  ) extends UploadingFile

  def apply(
      storage: Storage,
      info: UploadedFileInformation,
      contentLengthOpt: Option[Long]
  ): Either[SaveFileRejection.FileContentLengthIsMissing.type, UploadingFile] =
    storage match {
      case s: DiskStorage       =>
        Right(DiskUploadingFile(s.project, s.value.volume, s.value.algorithm, info.filename, info.contents))
      case s: RemoteDiskStorage =>
        Right(RemoteUploadingFile(s.project, s.value.folder, info.filename, info.contents))
      case s: S3Storage         =>
        contentLengthOpt.toRight(FileContentLengthIsMissing).map { contentLength =>
          S3UploadingFile(
            s.project,
            s.value.bucket,
            info.filename,
            info.suppliedContentType,
            contentLength,
            info.contents
          )
        }
    }
}
