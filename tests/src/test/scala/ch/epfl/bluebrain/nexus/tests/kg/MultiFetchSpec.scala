package ch.epfl.bluebrain.nexus.tests.kg

import akka.http.scaladsl.model.{ContentTypes, StatusCodes}
import ch.epfl.bluebrain.nexus.tests.BaseSpec
import ch.epfl.bluebrain.nexus.tests.Identity.listings.{Alice, Bob}
import ch.epfl.bluebrain.nexus.tests.iam.types.Permission.{Organizations, Resources}
import io.circe.Json
import ch.epfl.bluebrain.nexus.tests.Optics._

class MultiFetchSpec extends BaseSpec {

  private val org1   = genId()
  private val proj11 = genId()
  private val proj12 = genId()
  private val ref11  = s"$org1/$proj11"
  private val ref12  = s"$org1/$proj12"

  private val prefix = "https://bluebrain.github.io/nexus/vocabulary/"

  override def beforeAll(): Unit = {
    super.beforeAll()

    val setup = for {
      _ <- aclDsl.addPermission("/", Bob, Organizations.Create)
      _ <- adminDsl.createOrganization(org1, org1, Bob)
      _ <- adminDsl.createProject(org1, proj11, kgDsl.projectJson(name = proj11), Bob)
      _ <- adminDsl.createProject(org1, proj12, kgDsl.projectJson(name = proj12), Bob)
      _ <- aclDsl.addPermission(s"/$ref12", Alice, Resources.Read)
    } yield ()

    val resourcePayload =
      jsonContentOf(
        "/kg/resources/simple-resource.json",
        "priority" -> "5"
      )

    val createResources = for {
      // Creation
      _ <- deltaClient.put[Json](s"/resources/$ref11/_/nxv:resource", resourcePayload, Bob)(expectCreated)
      _ <- deltaClient.putAttachment[Json](
             s"/files/$ref12/nxv:file",
             contentOf("/kg/files/attachment.json"),
             ContentTypes.`application/json`,
             "attachment.json",
             Bob
           )(expectCreated)
      // Tag
      _ <- deltaClient.post[Json](s"/resources/$ref11/_/nxv:resource/tags?rev=1", tag("v1.0.0", 1), Bob)(expectCreated)
    } yield ()

    (setup >> createResources).accepted
  }

  "Fetching multiple resources" should {

    def request(format: String) =
      json"""
        {
          "format": "$format",
          "resources": [
            { "id": "${prefix}resource?tag=v1.0.0", "project": "$ref11" },
            { "id": "${prefix}file", "project": "$ref12" }
          ]
        }"""

    "get all resources for a user with all access" in {
      val expected = jsonContentOf(
        "/kg/multi-fetch/all-success.json",
        "project1" -> ref11,
        "project2" -> ref12
      )

      deltaClient.getWithBody[Json]("/multi-fetch/resources", request("source"), Bob) { (json, response) =>
        response.status shouldEqual StatusCodes.OK
        filterNestedKeys("_uuid")(json) shouldEqual expected
      }
    }

    "get all resources for a user with limited access" in {
      val expected = jsonContentOf(
        "/kg/multi-fetch/limited-access.json",
        "project1" -> ref11,
        "project2" -> ref12
      )

      deltaClient.getWithBody[Json]("/multi-fetch/resources", request("source"), Alice) { (json, response) =>
        response.status shouldEqual StatusCodes.OK
        filterNestedKeys("_uuid")(json) shouldEqual expected
      }
    }

    "get a not found error for an non-existing-resource" in {
      val request =
        json"""
        {
          "format": "source",
          "resources": [
            { "id": "${prefix}xxx", "project": "$ref11" }
          ]
        }"""

      val expected = jsonContentOf("/kg/multi-fetch/unknown.json", "project1" -> ref11)

      deltaClient.getWithBody[Json]("/multi-fetch/resources", request, Bob) { (json, response) =>
        response.status shouldEqual StatusCodes.OK
        json shouldEqual expected
      }
    }

  }

}
