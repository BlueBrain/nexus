package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3

import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.MediaType

/**
  * Options when copying an object in S3
  *
  * @param overwriteTarget
  *   if we want to overwrite the target if it exists
  * @param mediaType
  *   if we want to set a new content type on the created object
  */
final case class CopyOptions(overwriteTarget: Boolean, mediaType: Option[MediaType])
