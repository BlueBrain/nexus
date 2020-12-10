package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model

import ch.epfl.bluebrain.nexus.testkit.{EitherValuable, TestHelpers}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.util.Base64

class CryptoSpec extends AnyWordSpecLike with Matchers with TestHelpers with EitherValuable {

  private val secretKey = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"

  "The Crypto object" should {

    "always generate the same key for a given password" in {
      val crypto = Crypto("changeme", "salt")
      Base64.getEncoder.encodeToString(crypto.encoded) shouldEqual "FB4G2MHn/q6PXqpNkE1F5wBG7Ndsd9FtyeLcNQL0G40="
    }

    "encode and decode secrets" in {
      val crypto = Crypto(genString(32), genString(16))
      crypto.decrypt(crypto.encrypt(secretKey).rightValue).rightValue shouldEqual secretKey
    }
  }
}
