package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3

import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.Digest
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.DigestAlgorithm
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite
import software.amazon.awssdk.services.s3.model.HeadObjectResponse

class HeadObjectSuite extends NexusSuite {

  test("HeadObject should correctly parse a standard S3 SHA256 digest") {

    val multiPartDigest = "44ImQwqlEWtD75zMbO3GeJCOj4oO2lMb+VW6l6zJ3sc="
    val response        =
      HeadObjectResponse.builder().checksumSHA256(multiPartDigest).build()
    val digest          = HeadObject(response).digest

    assertEquals(
      digest,
      Digest.ComputedDigest(
        DigestAlgorithm.SHA256,
        "e38226430aa5116b43ef9ccc6cedc678908e8f8a0eda531bf955ba97acc9dec7"
      )
    )

  }

  test("HeadObject should correctly parse a multipart S3 SHA256 digest") {
    val multiPartDigest = "kFsM2p15+Jbp2K0FIF0y1zIWlEJOt5052qlU8IRQPtM=-13"
    val response        =
      HeadObjectResponse.builder().checksumSHA256(multiPartDigest).build()
    val digest          = HeadObject(response).digest

    assertEquals(
      digest,
      Digest.MultiPartDigest(
        DigestAlgorithm.SHA256,
        "905b0cda9d79f896e9d8ad05205d32d7321694424eb79d39daa954f084503ed3",
        13
      )
    )

  }

}
