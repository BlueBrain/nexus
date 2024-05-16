package ch.epfl.bluebrain.nexus.ship.files

import akka.http.scaladsl.model.Uri
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UrlUtils
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileAttributes
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.S3LocationGenerator
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.client.S3StorageClient
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.ship.config.FileProcessingConfig

import scala.concurrent.duration.DurationInt

trait FileCopier {

  def copyFile(project: ProjectRef, attributes: FileAttributes): IO[Option[Uri.Path]]

}

object FileCopier {

  private val logger = Logger[FileCopier.type]

  private val longCopyThreshold = 5.seconds

  def apply(
      s3StorageClient: S3StorageClient,
      config: FileProcessingConfig
  ): FileCopier = {
    val importBucket      = config.importBucket
    val targetBucket      = config.targetBucket
    val locationGenerator = new S3LocationGenerator(config.prefix.getOrElse(Uri.Empty))
    (project: ProjectRef, attributes: FileAttributes) => {
      val origin          = attributes.path
      val patchedFileName = if (attributes.filename.isEmpty) "file" else attributes.filename
      val target          = if (config.enableTargetRewrite) {
        locationGenerator.file(project, attributes.uuid, patchedFileName).path
      } else {
        if (attributes.filename.isEmpty) {
          origin ?/ patchedFileName
        } else origin
      }
      val FIVE_GB         = 5_000_000_000L

      val originKey = UrlUtils.decode(origin)
      val targetKey = UrlUtils.decode(target)

      def copy = {
        if (attributes.bytes >= FIVE_GB)
          s3StorageClient.copyObjectMultiPart(importBucket, originKey, targetBucket, targetKey).void
        else
          s3StorageClient.copyObject(importBucket, originKey, targetBucket, targetKey).void
      }.timed.flatMap { case (duration, _) =>
        IO.whenA(duration > longCopyThreshold)(
          logger.info(s"Copy file ${attributes.path} of size ${attributes.bytes} took ${duration.toSeconds} seconds.")
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
      } yield Option.when(isObject)(target)
    }
  }

  def apply(): FileCopier = (_: ProjectRef, attributes: FileAttributes) => IO.some(attributes.path)

}
