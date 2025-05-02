package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3

import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.MediaType
import software.amazon.awssdk.services.s3.model.PutObjectRequest as AwsPutObjectRequest

final case class PutObjectRequest(bucket: String, key: String, mediaType: Option[MediaType], contentLength: Long) {

  def asAws: AwsPutObjectRequest = {
    val request = AwsPutObjectRequest
      .builder()
      .bucket(bucket)
      .checksumAlgorithm(checksumAlgorithm)
      .contentLength(contentLength)
      .key(key)

    mediaType
      .fold(request) { mt => request.contentType(mt.tree) }
      .build()
  }

}
