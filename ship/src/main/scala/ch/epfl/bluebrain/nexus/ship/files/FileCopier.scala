package ch.epfl.bluebrain.nexus.ship.files

import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.model.Uri.Path
import cats.effect.IO
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategy.logError
import ch.epfl.bluebrain.nexus.delta.kernel.{Logger, RetryStrategy}
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UrlUtils
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileAttributes
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.{CopyOptions, CopyResult, S3LocationGenerator}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.client.S3StorageClient
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.ship.config.FileProcessingConfig
import ch.epfl.bluebrain.nexus.ship.files.FileCopier.FileCopyResult
import ch.epfl.bluebrain.nexus.ship.files.FileCopier.FileCopyResult.{FileCopySkipped, FileCopySuccess}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import software.amazon.awssdk.services.s3.model.S3Exception

import scala.concurrent.duration.DurationInt

trait FileCopier {

  def copyFile(project: ProjectRef, attributes: FileAttributes, forceContentType: Boolean): IO[FileCopyResult]

}

object FileCopier {

  private val logger = Logger[FileCopier.type]

  private val longCopyThreshold = 5.seconds

  private val copyRetryStrategy: RetryStrategy[S3Exception] = RetryStrategy.constant(
    30.seconds,
    10,
    e => e.statusCode() >= 500 && e.statusCode() < 600,
    logError(logger, "s3Copy")
  )

  sealed trait FileCopyResult extends Product with Serializable

  object FileCopyResult {

    final case class FileCopySuccess(newPath: Uri.Path) extends FileCopyResult

    final case object FileCopySkipped extends FileCopyResult

  }

  def apply(
      s3StorageClient: S3StorageClient,
      config: FileProcessingConfig
  ): FileCopier = {
    val importBucket      = config.importBucket
    val targetBucket      = config.targetBucket
    val locationGenerator = new S3LocationGenerator(config.prefix.getOrElse(Path.Empty))
    (project: ProjectRef, attributes: FileAttributes, forceContentType: Boolean) =>
      {
        val origin          = attributes.path
        val patchedFileName = if (attributes.filename.isEmpty) "file" else attributes.filename
        val target          = locationGenerator.file(project, attributes.uuid, patchedFileName).path
        val FIVE_GB         = 5_000_000_000L

        val originKey = UrlUtils.decode(origin)
        val targetKey = UrlUtils.decode(target)

        val copyOptions = CopyOptions(overwriteTarget = false, attributes.mediaType)

        def copy = {
          if (attributes.bytes >= FIVE_GB) {
            logger.info(s"Attempting to copy a large file from $importBucket/$originKey to $targetBucket/$targetKey") >>
              s3StorageClient.copyObjectMultiPart(importBucket, originKey, targetBucket, targetKey, copyOptions)
          } else
            s3StorageClient.copyObject(importBucket, originKey, targetBucket, targetKey, copyOptions)
        }.flatMap {
          case CopyResult.Success       => IO.unit
          case CopyResult.AlreadyExists =>
            IO.whenA(forceContentType) {
              attributes.mediaType.traverse { mediaType =>
                logger.info(s"Patching to content type $mediaType for file $patchedFileName")
                s3StorageClient.updateContentType(targetBucket, targetKey, mediaType)
              }.void
            }
        }.timed
          .flatMap { case (duration, _) =>
            IO.whenA(duration > longCopyThreshold)(
              logger.info(
                s"Copy file ${attributes.path} of size ${attributes.bytes} took ${duration.toSeconds} seconds."
              )
            )
          }

        for {
          isObject <- s3StorageClient.objectExists(importBucket, originKey)
          isFolder <-
            if (isObject) IO.pure(false) else s3StorageClient.listObjectsV2(importBucket, originKey).map(_.hasContents)
          _        <- IO.whenA(isObject) { copy }
          _        <- IO.whenA(isFolder) { logger.info(s"$target has been found to be a folder, skipping the file copy...") }
          _        <- IO.whenA(!isFolder && !isObject) {
                        logger.error(s"$target is neither an object or folder, something is wrong.")
                      }
        } yield if (isObject) FileCopySuccess(target) else FileCopySkipped
      }.retry(copyRetryStrategy)
  }

  def apply(): FileCopier = (_: ProjectRef, attributes: FileAttributes, _: Boolean) =>
    IO.pure(FileCopySuccess(attributes.path))

}
