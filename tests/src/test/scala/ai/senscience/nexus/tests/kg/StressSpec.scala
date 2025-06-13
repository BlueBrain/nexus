package ai.senscience.nexus.tests.kg

import ai.senscience.nexus.tests.BaseIntegrationSpec
import ai.senscience.nexus.tests.Identity.listings.Bob
import ai.senscience.nexus.tests.iam.types.Permission.Organizations
import akka.http.scaladsl.model.StatusCodes
import cats.implicits.*
import io.circe.Json

class StressSpec extends BaseIntegrationSpec {

  private val (org, proj) = (genId(), genId())
  private val ref         = s"$org/$proj"
  private val payload     = json"""{ "animal": "cat" }"""

  override def beforeAll(): Unit = {
    super.beforeAll()

    val setup = for {
      _ <- aclDsl.addPermission("/", Bob, Organizations.Create)
      _ <- adminDsl.createOrganization(org, org, Bob)
      _ <- adminDsl.createProjectWithName(org, proj, "projectName", Bob)
    } yield ()

    val createResources = for {
      _ <- deltaClient.post[Json](s"/resources/$ref/_/", payload, Bob)(expectCreated)
    } yield ()

    (setup >> createResources).accepted
  }

  "Querying an endpoint in parallel" should {

    "not break delta when posting synchronously" in {
      val postInParallel = (1 to 64).toList.parTraverse { i =>
        deltaClient.put[Json](s"/resources/$ref/_/res$i", payload, Bob) { (_, response) =>
          response.status shouldEqual StatusCodes.Created
        }
      }

      postInParallel >>
        deltaClient.get[Json](s"/resources/$ref/_/res1", Bob) { (_, response) =>
          response.status shouldEqual StatusCodes.OK
        }
    }

    "not break delta when reading" in {
      (1 to 64).toList.parTraverse { i =>
        deltaClient.get[Json](s"/resources/$ref/_/res$i", Bob) { (_, response) =>
          response.status shouldEqual StatusCodes.OK
        }
      }
    }

  }

}
