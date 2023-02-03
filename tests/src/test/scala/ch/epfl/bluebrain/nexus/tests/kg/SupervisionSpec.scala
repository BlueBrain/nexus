package ch.epfl.bluebrain.nexus.tests.kg

import akka.http.scaladsl.model.StatusCodes
import ch.epfl.bluebrain.nexus.tests.BaseSpec
import ch.epfl.bluebrain.nexus.tests.Identity.views.ScoobyDoo
import ch.epfl.bluebrain.nexus.tests.iam.types.Permission.Supervision
import io.circe.Json

class SupervisionSpec extends BaseSpec {

  "The supervision endpoint" should {
    s"require ${Supervision.Read.value} permission" in {
      deltaClient.get[Json]("/supervision/projections", ScoobyDoo) { (_, response) =>
        response.status shouldEqual StatusCodes.Forbidden
      }
    }

    "return running projections" in {
      aclDsl.addPermission("/", ScoobyDoo, Supervision.Read).accepted
      deltaClient.get[Json]("/supervision/projections", ScoobyDoo) { (json, response) =>
        response.status shouldEqual StatusCodes.OK
        val expected = jsonContentOf("/kg/supervision/projections.json")
        json should equalIgnoreArrayOrder(expected)
      }
    }
  }

}
