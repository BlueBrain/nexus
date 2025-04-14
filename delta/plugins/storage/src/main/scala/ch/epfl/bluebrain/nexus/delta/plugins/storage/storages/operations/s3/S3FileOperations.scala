package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3

import akka.actor.ActorSystem
import akka.http.scaladsl.model.{ContentType, Uri}
import akka.util.ByteString
import cats.effect.IO
import cats.syntax.all.*
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.kernel.utils.{UUIDF, UrlUtils}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileAttributes.FileAttributesOrigin
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileRejection.InvalidFilePath
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileStorageMetadata
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageFileRejection.FetchFileRejection
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageFileRejection.FetchFileRejection.UnexpectedFetchError
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.UploadingFile.S3UploadingFile
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.S3FileOperations.{S3DelegationMetadata, S3FileMetadata}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.client.S3StorageClient
import ch.epfl.bluebrain.nexus.delta.kernel.AkkaSource
import ch.epfl.bluebrain.nexus.delta.sdk.syntax.*
import ch.epfl.bluebrain.nexus.delta.sdk.stream.StreamConverter
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import software.amazon.awssdk.services.s3.model.NoSuchKeyException

trait S3FileOperations {

  def fetch(bucket: String, path: Uri.Path): IO[AkkaSource]

  def save(uploading: S3UploadingFile): IO[FileStorageMetadata]

  def link(bucket: String, path: Uri.Path): IO[S3FileMetadata]

  def delegate(bucket: String, project: ProjectRef, filename: String): IO[S3DelegationMetadata]
}

object S3FileOperations {

  type S3FileLink = (String, Uri.Path) => IO[S3FileMetadata]

  final case class S3FileMetadata(filename: String, contentType: Option[ContentType], metadata: FileStorageMetadata)
  final case class S3DelegationMetadata(bucket: String, path: Uri)

  private val log = Logger[S3FileOperations]

  def mk(client: S3StorageClient, locationGenerator: S3LocationGenerator)(implicit
      as: ActorSystem,
      uuidf: UUIDF
  ): S3FileOperations = new S3FileOperations {

    private lazy val saveFile = new S3StorageSaveFile(client, locationGenerator)

    override def fetch(bucket: String, path: Uri.Path): IO[AkkaSource] =
      IO.delay {
        StreamConverter(
          client
            .readFile(bucket, UrlUtils.decode(path))
            .map(bytes => ByteString(bytes))
            .adaptError {
              case _: NoSuchKeyException => FetchFileRejection.FileNotFound(path.toString)
              case err                   => UnexpectedFetchError(path.toString, err.getMessage)
            }
        )
      }

    override def save(uploading: S3UploadingFile): IO[FileStorageMetadata] = saveFile.save(uploading)

    override def link(bucket: String, path: Uri.Path): IO[S3FileMetadata] =
      linkInternal(client, bucket, path)

    override def delegate(bucket: String, project: ProjectRef, filename: String): IO[S3DelegationMetadata] =
      uuidf().map { uuid =>
        S3DelegationMetadata(bucket, locationGenerator.file(project, uuid, filename))
      }
  }

  def linkInternal(client: S3StorageClient, bucket: String, path: Uri.Path)(implicit
      uuidF: UUIDF
  ): IO[S3FileMetadata] = {
    for {
      _        <- log.debug(s"Fetching attributes for S3 file. Bucket $bucket at path $path")
      resp     <- client.headObject(bucket, UrlUtils.decode(path))
      metadata <- mkS3Metadata(path, resp)
    } yield metadata
  }
    .onError { case e =>
      log.error(e)(s"Failed fetching required attributes for S3 file registration. Bucket $bucket and path $path")
    }

  private def mkS3Metadata(path: Uri.Path, resp: HeadObject)(implicit
      uuidf: UUIDF
  ) =
    for {
      uuid     <- uuidf()
      filename <- IO.fromOption(path.lastSegment)(InvalidFilePath)
    } yield S3FileMetadata(
      filename,
      resp.contentType,
      FileStorageMetadata(
        uuid,
        resp.fileSize,
        resp.digest,
        FileAttributesOrigin.Link,
        Uri(path.toString()),
        path
      )
    )

}
