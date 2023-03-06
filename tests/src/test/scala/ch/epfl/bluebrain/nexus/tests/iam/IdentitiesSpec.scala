package ch.epfl.bluebrain.nexus.tests.iam

import akka.http.scaladsl.model.StatusCodes
import ch.epfl.bluebrain.nexus.tests.HttpClient.tokensMap
import ch.epfl.bluebrain.nexus.tests.{BaseSpec, Identity}
import io.circe.Json

class IdentitiesSpec extends BaseSpec {

  "The /identities endpoint" should {
    s"return identities of the user" in {
      deltaClient.get[Json]("/identities", Identity.ServiceAccount) { (json, response) =>
        response.status shouldEqual StatusCodes.OK
        json shouldEqual jsonContentOf(
          "/iam/identities/response.json",
          "deltaUri" -> config.deltaUri.toString()
        )
      }
    }

    "return the error for an invalid token" in {
      tokensMap.put(Identity.InvalidTokenUser, toAuthorizationHeader("INVALID"))

      deltaClient.get[Json]("/identities", Identity.InvalidTokenUser) { (json, response) =>
        response.status shouldEqual StatusCodes.Unauthorized
        json shouldEqual jsonContentOf("/iam/identities/errors.json")
      }
    }
  }
}
