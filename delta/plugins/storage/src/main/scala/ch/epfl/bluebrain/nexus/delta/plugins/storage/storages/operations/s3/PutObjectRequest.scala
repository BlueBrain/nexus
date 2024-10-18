package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3

import akka.http.scaladsl.model.ContentType
import software.amazon.awssdk.services.s3.model.{PutObjectRequest => AwsPutObjectRequest}

final case class PutObjectRequest(bucket: String, key: String, contentType: Option[ContentType], contentLength: Long) {

  def asAws: AwsPutObjectRequest = {
    val request = AwsPutObjectRequest
      .builder()
      .bucket(bucket)
      .checksumAlgorithm(checksumAlgorithm)
      .contentLength(contentLength)
      .key(key)

    contentType
      .fold(request) { ct =>
        request.contentType(ct.value)
      }
      .build()
  }

}
