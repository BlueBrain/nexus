package ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model

import akka.http.scaladsl.model.HttpEntity

/**
  * Request to upload a file during create/update operations
  * @param entity
  *   the form data entity
  * @param metadata
  *   the optional custom metadata provided by the user
  * @param contentLength
  *   the optional content length which becomes required when pushing a file to a S3 storage so as to compute locally
  *   the checksum
  */
final case class FileUploadRequest(
    entity: HttpEntity,
    metadata: Option[FileCustomMetadata],
    contentLength: Option[Long]
)

object FileUploadRequest {

  def from(entity: HttpEntity): FileUploadRequest = FileUploadRequest(entity, None, None)

}
