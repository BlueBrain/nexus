package ch.epfl.bluebrain.nexus.iam.types

import ch.epfl.bluebrain.nexus.util.{EitherValues, Resources}
import ch.epfl.bluebrain.nexus.iam.config.AppConfig.HttpConfig
import ch.epfl.bluebrain.nexus.iam.types.Identity.{Anonymous, Authenticated, Group, Subject, User}
import io.circe.syntax._
import org.scalatest.Inspectors
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class IdentitySpec extends AnyWordSpecLike with Matchers with Inspectors with EitherValues with Resources {

  "An Identity" should {
    val user          = User("mysubject", "myrealm")
    val group         = Group("mygroup", "myrealm")
    val authenticated = Authenticated("myrealm")

    implicit val http: HttpConfig = HttpConfig("some", 8080, "v1", "http://nexus.example.com")

    "converted to Json" in {
      val userJson          = jsonContentOf("/identities/produce/user.json")
      val groupJson         = jsonContentOf("/identities/produce/group.json")
      val authenticatedJson = jsonContentOf("/identities/produce/authenticated.json")
      val anonymousJson     = jsonContentOf("/identities/produce/anonymous.json")

      val cases =
        List(user -> userJson, group -> groupJson, Anonymous -> anonymousJson, authenticated -> authenticatedJson)

      forAll(cases) {
        case (model: Subject, json) =>
          model.asJson shouldEqual json
          (model: Identity).asJson shouldEqual json
        case (model: Identity, json) => model.asJson shouldEqual json
      }
    }
    "convert from Json" in {
      val userJson          = jsonContentOf("/identities/consume/user.json")
      val groupJson         = jsonContentOf("/identities/consume/group.json")
      val authenticatedJson = jsonContentOf("/identities/consume/authenticated.json")
      val anonymousJson     = jsonContentOf("/identities/consume/anonymous.json")
      val cases =
        List(user -> userJson, group -> groupJson, Anonymous -> anonymousJson, authenticated -> authenticatedJson)
      forAll(cases) {
        case (model: Subject, json) =>
          json.as[Subject].rightValue shouldEqual model
          json.as[Identity].rightValue shouldEqual (model: Identity)
        case (model: Identity, json) => json.as[Identity].rightValue shouldEqual model

      }
    }
  }
}
