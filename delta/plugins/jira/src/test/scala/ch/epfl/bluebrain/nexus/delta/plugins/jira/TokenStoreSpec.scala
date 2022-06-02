package ch.epfl.bluebrain.nexus.delta.plugins.jira

import ch.epfl.bluebrain.nexus.delta.plugins.jira.OAuthToken.{AccessToken, RequestToken}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.User
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.testkit.{IOFixedClock, IOValues, ShouldMatchers, TestHelpers}
import org.scalatest.OptionValues
import org.scalatest.wordspec.AnyWordSpecLike

trait TokenStoreSpec extends IOFixedClock with IOValues with OptionValues with TestHelpers with ShouldMatchers {
  this: AnyWordSpecLike =>

  def tokenStore: TokenStore

  "A store" should {

    val user = User("Alice", Label.unsafe("Wonderland"))

    val request = RequestToken("request")
    val access  = AccessToken("access")

    "return none if no token exist for the " in {
      tokenStore.get(user).accepted shouldEqual None
    }

    "save a given token" in {
      tokenStore.save(user, request).accepted
    }

    "get a token" in {
      tokenStore.get(user).accepted.value shouldEqual request
    }

    "overwrite an existing token" in {
      tokenStore.save(user, access).accepted
      tokenStore.get(user).accepted.value shouldEqual access
    }

  }

}
