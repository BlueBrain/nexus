package ch.epfl.bluebrain.nexus.delta.plugins.jira

import ch.epfl.bluebrain.nexus.delta.plugins.jira.OAuthToken.{AccessToken, RequestToken}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.User
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.delta.sourcing.postgres.Doobie
import ch.epfl.bluebrain.nexus.testkit.ce.IOFixedClock
import ch.epfl.bluebrain.nexus.testkit.mu.ce.CatsEffectSuite
import munit.AnyFixture

class TokenStoreSuite extends CatsEffectSuite with Doobie.Fixture with IOFixedClock {

  override def munitFixtures: Seq[AnyFixture[_]] = List(doobie)

  private lazy val xas = doobie()

  private lazy val tokenStore: TokenStore = TokenStore(xas)

  private val user = User("Alice", Label.unsafe("Wonderland"))

  private val request = RequestToken("request")
  private val access  = AccessToken("access")

  test("Return none if no token exist for the user") {
    tokenStore.get(user).assertEquals(None)
  }

  test("Save a given token for the user and return it") {
    for {
      _ <- tokenStore.save(user, request)
      _ <- tokenStore.get(user).assertEquals(Some(request))
    } yield ()
  }

  test("Overwrite an existing token for the user") {
    for {
      _ <- tokenStore.save(user, access)
      _ <- tokenStore.get(user).assertEquals(Some(access))
    } yield ()
  }

}
