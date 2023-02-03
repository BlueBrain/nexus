package ch.epfl.bluebrain.nexus.tests.kg

import akka.http.scaladsl.model.StatusCodes
import ch.epfl.bluebrain.nexus.tests.BaseSpec
import ch.epfl.bluebrain.nexus.tests.Identity.views.ScoobyDoo
import ch.epfl.bluebrain.nexus.tests.iam.types.Permission.Supervision
import io.circe.Json

class SupervisionSpec extends BaseSpec {

  "The supervision endpoint" should {
    s"reject calls without ${Supervision.Read.value} permission" in {
      deltaClient.get[Json]("/supervision/projections", ScoobyDoo) { (_, response) =>
        response.status shouldEqual StatusCodes.Forbidden
      }
    }

    s"accept calls with ${Supervision.Read.value}" in {
      aclDsl.addPermission("/", ScoobyDoo, Supervision.Read).accepted
      deltaClient.get[Json]("/supervision/projections", ScoobyDoo) { (_, response) =>
        response.status shouldEqual StatusCodes.OK
      }
    }
  }

}
