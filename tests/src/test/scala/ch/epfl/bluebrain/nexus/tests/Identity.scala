package ch.epfl.bluebrain.nexus.tests

import ch.epfl.bluebrain.nexus.testkit.TestHelpers

sealed trait Identity extends Product with Serializable

object Identity extends TestHelpers {

  case object Anonymous extends Identity

  sealed trait Authenticated extends Identity {
    def name: String

    def realm: Realm
  }

  final case class UserCredentials(name: String, password: String, realm: Realm) extends Authenticated

  final case class ClientCredentials(id: String, name: String, secret: String, realm: Realm) extends Authenticated

  object ClientCredentials {
    def apply(id: String, secret: String, realm: Realm): ClientCredentials =
      new ClientCredentials(id, s"service-account-$id", secret, realm)
  }

  import Realm._

  // Client
  val ServiceAccount: ClientCredentials = ClientCredentials("delta", "shhh", internal)

  val Delta: UserCredentials = UserCredentials("delta", "shhh", internal)

  val testRealm  = Realm("test-" + genString())
  val testClient = Identity.ClientCredentials(genString(), genString(), testRealm)

  // User with an invalid token
  val InvalidTokenUser: UserCredentials = UserCredentials(genString(), genString(), testRealm)

  object acls {
    val Marge = UserCredentials(genString(), genString(), testRealm)
  }

  object archives {
    val Tweety = UserCredentials(genString(), genString(), testRealm)
  }

  object compositeviews {
    val Jerry = UserCredentials(genString(), genString(), testRealm)
  }

  object events {
    val BugsBunny = UserCredentials(genString(), genString(), testRealm)
  }

  object listings {
    val Bob   = UserCredentials(genString(), genString(), testRealm)
    val Alice = UserCredentials(genString(), genString(), testRealm)
  }

  object orgs {
    val Fry   = UserCredentials(genString(), genString(), testRealm)
    val Leela = UserCredentials(genString(), genString(), testRealm)
  }

  object projects {
    val Bojack          = UserCredentials(genString(), genString(), testRealm)
    val PrincessCarolyn = UserCredentials(genString(), genString(), testRealm)
  }

  object resources {
    val Rick  = UserCredentials(genString(), genString(), testRealm)
    val Morty = UserCredentials(genString(), genString(), testRealm)
  }

  object storages {
    val Coyote = UserCredentials(genString(), genString(), testRealm)
  }

  object views {
    val ScoobyDoo = UserCredentials(genString(), genString(), testRealm)
  }

  object mash {
    val Radar = UserCredentials(genString(), genString(), testRealm)
  }

  object supervision {
    val Mickey = UserCredentials(genString(), genString(), testRealm)
  }

  lazy val allUsers =
    acls.Marge :: archives.Tweety :: compositeviews.Jerry :: events.BugsBunny :: listings.Bob :: listings.Alice :: orgs.Fry :: orgs.Leela :: projects.Bojack :: projects.PrincessCarolyn :: resources.Rick :: resources.Morty :: storages.Coyote :: views.ScoobyDoo :: mash.Radar :: supervision.Mickey :: Nil

}
