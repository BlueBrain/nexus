package ch.epfl.bluebrain.nexus.storage.client

import ch.epfl.bluebrain.nexus.commons.test.EitherValues
import ch.epfl.bluebrain.nexus.rdf.Iri.Urn
import ch.epfl.bluebrain.nexus.rdf.implicits._
import ch.epfl.bluebrain.nexus.storage.client.config.StorageClientConfig
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class StorageClientConfigSpec extends AnyWordSpecLike with Matchers with EitherValues {

  "A StorageClientConfig" should {

    "build from Url" in {
      StorageClientConfig(url"http://example.com/subresource/v1") shouldEqual
        StorageClientConfig(url"http://example.com/subresource", "v1")

      StorageClientConfig(url"http://example.com/v1") shouldEqual
        StorageClientConfig(url"http://example.com", "v1")
    }

    "build from Urn" in {
      StorageClientConfig(Urn("urn:ietf:rfc:2648/subresource/v1").rightValue) shouldEqual
        (StorageClientConfig(Urn("urn:ietf:rfc:2648/subresource").rightValue, "v1"))
    }
  }
}
