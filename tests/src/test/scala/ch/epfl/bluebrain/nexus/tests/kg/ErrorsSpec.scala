package ch.epfl.bluebrain.nexus.tests.kg

import akka.http.scaladsl.model.StatusCodes
import ch.epfl.bluebrain.nexus.testkit.scalatest.EitherValuable
import ch.epfl.bluebrain.nexus.tests.{BaseSpec, Identity}
import io.circe.Json

class ErrorsSpec extends BaseSpec with EitherValuable {

  "The /errors/invalid endpoint" should {
    s"return the proper error code" in {
      deltaClient.get[Json]("/errors/invalid", Identity.Anonymous) { (json, response) =>
        response.status shouldEqual StatusCodes.Forbidden
        json shouldEqual jsonContentOf("/iam/errors/unauthorized-access.json")
      }
    }
  }

}
