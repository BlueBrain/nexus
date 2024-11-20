package ch.epfl.bluebrain.nexus.ship.files

import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.model.Uri.Path
import cats.effect.IO
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategy.logError
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UrlUtils
import ch.epfl.bluebrain.nexus.delta.kernel.{Logger, RetryStrategy}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileAttributes
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.client.S3StorageClient
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.{CopyOptions, S3LocationGenerator}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.ship.config.FileProcessingConfig
import ch.epfl.bluebrain.nexus.ship.files.FileCopier.FileCopyResult
import ch.epfl.bluebrain.nexus.ship.files.FileCopier.FileCopyResult.{FileCopySkipped, FileCopySuccess}
import software.amazon.awssdk.services.s3.model.S3Exception

import java.net.URI
import java.nio.file.Paths
import scala.concurrent.duration.DurationInt

trait FileCopier {

  def copyFile(project: ProjectRef, attributes: FileAttributes, localOrigin: Boolean): IO[FileCopyResult]

}

object FileCopier {

  private val logger = Logger[FileCopier.type]

  private val longCopyThreshold = 5.seconds

  private val copyRetryStrategy: RetryStrategy[S3Exception] = RetryStrategy.constant(
    30.seconds,
    10,
    e => e.statusCode() == 403 || (e.statusCode() >= 500 && e.statusCode() < 600),
    logError(logger, "s3Copy")
  )

  def localDiskPath(relative: Path): String = Paths.get(URI.create(s"file:/$relative")).toString.drop(1)

  sealed trait FileCopyResult extends Product with Serializable

  object FileCopyResult {

    final case class FileCopySuccess(newPath: Uri.Path) extends FileCopyResult

    final case object FileCopySkipped extends FileCopyResult

  }

  def computeOriginKey(
      s3StorageClient: S3StorageClient,
      importBucket: String,
      path: Path,
      localOrigin: Boolean
  ): IO[Option[String]] = {
    def exists(key: String) = s3StorageClient.objectExists(importBucket, key).flatMap {
      case true  => IO.some(key)
      case false =>
        s3StorageClient
          .listObjectsV2(importBucket, key)
          .map(_.hasContents)
          .flatMap { isFolder =>
            IO.whenA(isFolder) {
              logger.info(s"'$key' has been found to be a folder, skipping the file copy...")
            }
          }
          .as(None)
    }

    val decodedKey = if (localOrigin) localDiskPath(path) else UrlUtils.decode(path)
    exists(decodedKey).flatMap {
      case Some(key) => IO.some(key)
      case None      => exists(path.toString())
    }
  }

  def apply(
      s3StorageClient: S3StorageClient,
      config: FileProcessingConfig
  ): FileCopier = {
    val importBucket      = config.importBucket
    val targetBucket      = config.targetBucket
    val locationGenerator = new S3LocationGenerator(config.prefix.getOrElse(Path.Empty))
    (project: ProjectRef, attributes: FileAttributes, localOrigin: Boolean) =>
      {
        val path            = attributes.path
        val patchedFileName = if (attributes.filename.isEmpty) "file" else attributes.filename
        val target          = locationGenerator.file(project, attributes.uuid, patchedFileName).path
        val FIVE_GB         = 5_000_000_000L

        val targetKey   = UrlUtils.decode(target)
        val copyOptions = CopyOptions(overwriteTarget = false, attributes.mediaType)

        def copy(originKey: String) = {
          if (attributes.bytes >= FIVE_GB) {
            logger.info(s"Attempting to copy a large file from $importBucket/$originKey to $targetBucket/$targetKey") >>
              s3StorageClient.copyObjectMultiPart(importBucket, originKey, targetBucket, targetKey, copyOptions)
          } else
            s3StorageClient.copyObject(importBucket, originKey, targetBucket, targetKey, copyOptions)
        }.timed
          .flatMap { case (duration, _) =>
            IO.whenA(duration > longCopyThreshold)(
              logger.info(
                s"Copy file ${attributes.path} of size ${attributes.bytes} took ${duration.toSeconds} seconds."
              )
            )
          }

        for {
          originKey <- computeOriginKey(s3StorageClient, importBucket, path, localOrigin)
          _         <- originKey.traverse(copy)
          _         <- IO.whenA(originKey.isEmpty) {
                         logger.error(s"$path is neither an object or folder, something is wrong.")
                       }
        } yield if (originKey.isDefined) FileCopySuccess(target) else FileCopySkipped
      }.retry(copyRetryStrategy)
  }

  def apply(): FileCopier = (_: ProjectRef, attributes: FileAttributes, _: Boolean) =>
    IO.pure(FileCopySuccess(attributes.path))

}
