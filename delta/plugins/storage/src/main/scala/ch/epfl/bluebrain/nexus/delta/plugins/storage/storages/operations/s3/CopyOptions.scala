package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3

import akka.http.scaladsl.model.ContentType

/**
  * Options when copying an object in S3
  * @param overwriteTarget
  *   if we want to overwrite the target if it exists
  * @param newContentType
  *   if we want to set a new content type on the created object
  */
final case class CopyOptions(overwriteTarget: Boolean, newContentType: Option[ContentType])
