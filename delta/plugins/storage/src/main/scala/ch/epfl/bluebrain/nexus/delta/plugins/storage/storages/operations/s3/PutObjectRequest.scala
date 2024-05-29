package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3

import akka.http.scaladsl.model.ContentType
import software.amazon.awssdk.services.s3.model.{PutObjectRequest => AwsPutObjectRequest}

final case class PutObjectRequest(bucket: String, key: String, contentType: ContentType, contentLength: Long) {

  def asAws: AwsPutObjectRequest = AwsPutObjectRequest
    .builder()
    .bucket(bucket)
    .checksumAlgorithm(checksumAlgorithm)
    .contentType(contentType.value)
    .contentLength(contentLength)
    .key(key)
    .build()

}
