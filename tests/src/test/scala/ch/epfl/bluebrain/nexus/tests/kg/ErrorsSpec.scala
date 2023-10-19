package ch.epfl.bluebrain.nexus.tests.kg

import akka.http.scaladsl.model.StatusCodes
import ch.epfl.bluebrain.nexus.testkit.scalatest.EitherValues
import ch.epfl.bluebrain.nexus.tests.{BaseIntegrationSpec, Identity}
import io.circe.Json

class ErrorsSpec extends BaseIntegrationSpec with EitherValues {

  "The /errors/invalid endpoint" should {
    s"return the proper error code" in {
      deltaClient.get[Json]("/errors/invalid", Identity.Anonymous) { (json, response) =>
        response.status shouldEqual StatusCodes.Forbidden
        json shouldEqual jsonContentOf("/iam/errors/unauthorized-access.json")
      }
    }
  }

}
