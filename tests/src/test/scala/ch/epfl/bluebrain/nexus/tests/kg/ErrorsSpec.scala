package ch.epfl.bluebrain.nexus.tests.kg

import ch.epfl.bluebrain.nexus.tests.{BaseIntegrationSpec, Identity}
import io.circe.Json

class ErrorsSpec extends BaseIntegrationSpec {

  "The /errors/invalid endpoint" should {
    s"return the proper error code" in {
      deltaClient.get[Json]("/errors/invalid", Identity.Anonymous) { expectForbidden }
    }
  }

}
