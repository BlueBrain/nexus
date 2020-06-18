package ch.epfl.bluebrain.nexus.kg.storage

import java.util.Base64

import ch.epfl.bluebrain.nexus.commons.test.Randomness
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class CryptoSpec extends AnyWordSpecLike with Matchers with Randomness {

  private val accessKey = "AKIAIOSFODNN7EXAMPLE"
  private val secretKey = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"

  "The Crypto object" should {

    "always generate the same key for a given password" in {
      val key = Crypto.deriveKey("changeme", "salt")
      Base64.getEncoder.encodeToString(key.getEncoded) shouldEqual "FB4G2MHn/q6PXqpNkE1F5wBG7Ndsd9FtyeLcNQL0G40="
      key.getAlgorithm shouldEqual "AES"
      key.getFormat shouldEqual "RAW"
    }

    "encode and decode secrets" in {
      val key = Crypto.deriveKey(genString(32), genString(16))
      Crypto.decrypt(key, Crypto.encrypt(key, accessKey)) shouldEqual accessKey
      Crypto.decrypt(key, Crypto.encrypt(key, secretKey)) shouldEqual secretKey
    }
  }
}
