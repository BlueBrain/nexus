package ch.epfl.bluebrain.nexus.tests.plugins.blazegraph

import akka.http.scaladsl.model.StatusCodes
import ch.epfl.bluebrain.nexus.tests.BaseIntegrationSpec
import ch.epfl.bluebrain.nexus.tests.Identity.{Anonymous, ServiceAccount}
import ch.epfl.bluebrain.nexus.tests.iam.types.Permission.Supervision
import io.circe.Json

class BlazegraphSupervisionSpec extends BaseIntegrationSpec {

  "The supervision endpoint" should {
    s"reject calls without ${Supervision.Read.value} permission" in {
      deltaClient.get[Json]("/supervision/projections", Anonymous) { (_, response) =>
        response.status shouldEqual StatusCodes.Forbidden
      }
    }

    s"accept calls with ${Supervision.Read.value}" in {
      deltaClient.get[Json]("/supervision/projections", ServiceAccount) { (_, response) =>
        response.status shouldEqual StatusCodes.OK
      }
    }
  }

}
