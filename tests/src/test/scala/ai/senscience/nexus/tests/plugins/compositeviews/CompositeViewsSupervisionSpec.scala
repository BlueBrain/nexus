package ai.senscience.nexus.tests.plugins.compositeviews

import ai.senscience.nexus.tests.BaseIntegrationSpec
import ai.senscience.nexus.tests.Identity.{Anonymous, ServiceAccount}
import ai.senscience.nexus.tests.iam.types.Permission.Supervision
import io.circe.Json

class CompositeViewsSupervisionSpec extends BaseIntegrationSpec {

  "The supervision endpoint" should {
    s"reject calls without ${Supervision.Read.value} permission" in {
      deltaClient.get[Json]("/supervision/composite-views", Anonymous) { expectForbidden }
    }

    s"accept calls with ${Supervision.Read.value}" in {
      deltaClient.get[Json]("/supervision/composite-views", ServiceAccount) { expectOk }
    }
  }

}
