package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3

import akka.http.scaladsl.model.ContentType
import ch.epfl.bluebrain.nexus.delta.kernel.Hex
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.Digest
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.Digest.ComputedDigest
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.DigestAlgorithm
import software.amazon.awssdk.services.s3.model.HeadObjectResponse

import java.util.Base64

case class HeadObject(fileSize: Long, contentType: Option[ContentType], digest: Digest)

object HeadObject {
  def apply(response: HeadObjectResponse): HeadObject = {
    val contentType = Option(response.contentType()).flatMap { value =>
      // It is highly likely for S3 to return an erroneous value here
      ContentType.parse(value).toOption
    }
    val digestValue = Option(response.checksumSHA256).map { encodedChecksum =>
      Hex.valueOf(Base64.getDecoder.decode(encodedChecksum))
    }
    val digest      = digestValue.fold(Digest.none) { value =>
      ComputedDigest(DigestAlgorithm.SHA256, value)
    }
    HeadObject(
      response.contentLength(),
      contentType,
      digest
    )
  }
}
