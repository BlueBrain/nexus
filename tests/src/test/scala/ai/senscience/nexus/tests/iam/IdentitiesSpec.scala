package ai.senscience.nexus.tests.iam

import ai.senscience.nexus.tests.HttpClient.tokensMap
import ai.senscience.nexus.tests.{BaseIntegrationSpec, Identity}
import akka.http.scaladsl.model.StatusCodes
import io.circe.Json

class IdentitiesSpec extends BaseIntegrationSpec {

  "The /identities endpoint" should {
    s"return identities of the user" in {
      deltaClient.get[Json]("/identities", Identity.ServiceAccount) { (json, response) =>
        response.status shouldEqual StatusCodes.OK
        json shouldEqual jsonContentOf(
          "iam/identities/response.json",
          "deltaUri" -> config.deltaUri.toString()
        )
      }
    }

    "return the error for an invalid token" in {
      tokensMap.put(Identity.InvalidTokenUser, toAuthorizationHeader("INVALID"))

      deltaClient.get[Json]("/identities", Identity.InvalidTokenUser) { (json, response) =>
        response.status shouldEqual StatusCodes.Unauthorized
        json.asObject.flatMap(_("reason")) should not be empty
      }
    }
  }
}
