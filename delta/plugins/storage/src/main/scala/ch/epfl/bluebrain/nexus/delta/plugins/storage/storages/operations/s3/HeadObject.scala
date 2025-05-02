package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3

import ch.epfl.bluebrain.nexus.delta.kernel.Hex
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.{Digest, MediaType}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.Digest.ComputedDigest
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.DigestAlgorithm
import software.amazon.awssdk.services.s3.model.HeadObjectResponse

import java.util.Base64

case class HeadObject(fileSize: Long, mediaType: Option[MediaType], digest: Digest)

object HeadObject {
  def apply(response: HeadObjectResponse): HeadObject = {
    val mediaType = Option(response.contentType()).flatMap { value =>
      // It is highly likely for S3 to return an erroneous value here
      MediaType.parse(value).toOption
    }

    val digest = Option(response.checksumSHA256())
      .map { encodedChecksum =>
        val multiPartDigest = """^(.*)-(\d+)$""".r
        encodedChecksum match {
          case multiPartDigest(value, parts) =>
            val digestValue = Hex.valueOf(Base64.getDecoder.decode(value))
            Digest.MultiPartDigest(DigestAlgorithm.SHA256, digestValue, parts.toInt)
          case _                             =>
            val digestValue = Hex.valueOf(Base64.getDecoder.decode(encodedChecksum))
            ComputedDigest(DigestAlgorithm.SHA256, digestValue)
        }
      }
      .getOrElse(Digest.none)

    HeadObject(
      response.contentLength(),
      mediaType,
      digest
    )
  }
}
