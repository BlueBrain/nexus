package ch.epfl.bluebrain.nexus.ship.files

import akka.http.scaladsl.model.Uri
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileAttributes
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.S3LocationGenerator
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.client.S3StorageClient
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.ship.config.FileProcessingConfig

import scala.concurrent.duration.DurationInt

trait FileCopier {

  def copyFile(project: ProjectRef, attributes: FileAttributes): IO[Unit]

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
    (project: ProjectRef, attributes: FileAttributes) =>
      {
        val origin  = attributes.path.toString
        val target  = if (config.enableTargetRewrite) {
          locationGenerator.file(project, attributes.uuid, attributes.filename).toString
        } else {
          origin
        }
        val FIVE_GB = 5_000_000_000L

        // TODO: Check if we only use SHA256 or not? If not we need to pass the right algo
        if (attributes.bytes >= FIVE_GB)
          s3StorageClient.copyObjectMultiPart(importBucket, origin, targetBucket, target).void
        else
          s3StorageClient.copyObject(importBucket, origin, targetBucket, target).void
      }.timed.flatMap { case (duration, _) =>
        IO.whenA(duration > longCopyThreshold)(
          logger.info(s"Copy file ${attributes.path} of size ${attributes.bytes} took ${duration.toSeconds} seconds.")
        )
      }
  }

  def apply(): FileCopier = (_: ProjectRef, _: FileAttributes) => IO.unit

}
