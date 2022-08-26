package ch.epfl.bluebrain.nexus.delta.plugins.jira

import ch.epfl.bluebrain.nexus.delta.plugins.jira.OAuthToken.{AccessToken, RequestToken}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.User
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.testkit._
import org.scalatest.OptionValues

class TokenStoreSpec
    extends DoobieScalaTestFixture
    with IOFixedClock
    with IOValues
    with OptionValues
    with TestHelpers
    with ShouldMatchers {

  private lazy val tokenStore: TokenStore = TokenStore(xas, tablesAutocreate = true).accepted

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
