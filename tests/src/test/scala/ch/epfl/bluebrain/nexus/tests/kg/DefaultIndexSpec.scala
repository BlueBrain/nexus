package ch.epfl.bluebrain.nexus.tests.kg

import akka.http.scaladsl.model.StatusCodes
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UrlUtils
import ch.epfl.bluebrain.nexus.tests.BaseIntegrationSpec
import ch.epfl.bluebrain.nexus.tests.Identity.listings.{Alice, Bob}
import ch.epfl.bluebrain.nexus.tests.Optics.{filterNestedKeys, hitProjects}
import ch.epfl.bluebrain.nexus.tests.admin.ProjectPayload
import ch.epfl.bluebrain.nexus.tests.iam.types.Permission.Organizations
import io.circe.Json

class DefaultIndexSpec extends BaseIntegrationSpec {

  private val org1   = genId()
  private val proj11 = genId()
  private val proj12 = genId()
  private val ref11  = s"$org1/$proj11"

  override def beforeAll(): Unit = {
    super.beforeAll()

    val setup = for {
      _ <- aclDsl.addPermission("/", Bob, Organizations.Create)
      // First org and projects
      _ <- adminDsl.createOrganization(org1, org1, Bob)
      _ <- adminDsl.createProject(org1, proj11, ProjectPayload.generate(proj11), Bob)
      _ <- adminDsl.createProject(org1, proj12, ProjectPayload.generate(proj12), Bob)
    } yield ()
    setup.accepted
  }

  val defaultViewsId = UrlUtils.encode("https://bluebrain.github.io/nexus/vocabulary/defaultElasticSearchIndex")

  "Getting default indexing statistics" should {

    "get an error if the user has no access" in {
      deltaClient.get[Json](s"/views/$ref11/$defaultViewsId/statistics", Alice) { (_, response) =>
        response.status shouldEqual StatusCodes.Forbidden
      }
    }

    "get the statistics if the user has access" in eventually {
      deltaClient.get[Json](s"/views/$ref11/$defaultViewsId/statistics", Bob) { (json, response) =>
        response.status shouldEqual StatusCodes.OK
        val expected = jsonContentOf(
          "kg/views/statistics.json",
          "total"     -> "5",
          "processed" -> "5",
          "evaluated" -> "5",
          "discarded" -> "0",
          "remaining" -> "0"
        )
        filterNestedKeys("lastEventDateTime", "lastProcessedEventDateTime")(json) shouldEqual expected
      }
    }
  }

  "Searching on the default" should {

    val matchAll = json"""{"query": { "match_all": {} } }"""

    "get an error for a user with no access" in {
      deltaClient.post[Json](s"/views/$ref11/$defaultViewsId/_search", matchAll, Alice) { (_, response) =>
        response.status shouldEqual StatusCodes.Forbidden
      }
    }

    s"get a response with only resources from project '$ref11'" in eventually {
      deltaClient.post[Json](s"/views/$ref11/$defaultViewsId/_search", matchAll, Bob) { (json, response) =>
        response.status shouldEqual StatusCodes.OK
        hitProjects.getAll(json) should contain only s"${config.deltaUri}/projects/$ref11"
      }
    }
  }
}
