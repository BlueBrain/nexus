package ai.senscience.nexus.tests.kg

import ai.senscience.nexus.tests.{BaseIntegrationSpec, Identity}
import io.circe.Json

class ErrorsSpec extends BaseIntegrationSpec {

  "The /errors/invalid endpoint" should {
    s"return the proper error code" in {
      deltaClient.get[Json]("/errors/invalid", Identity.Anonymous) { expectForbidden }
    }
  }

}
