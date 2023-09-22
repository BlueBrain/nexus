package ch.epfl.bluebrain.nexus.tests.kg

import akka.http.scaladsl.model.{ContentTypes, HttpResponse, StatusCodes}
import ch.epfl.bluebrain.nexus.tests.{BaseSpec, Identity}
import ch.epfl.bluebrain.nexus.tests.Identity.listings.{Alice, Bob}
import ch.epfl.bluebrain.nexus.tests.Optics._
import ch.epfl.bluebrain.nexus.tests.iam.types.Permission.Resources
import ch.epfl.bluebrain.nexus.tests.resources.SimpleResource
import io.circe.Json
import org.scalatest.Assertion

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
      _ <- createProjects(Bob, org1, proj11, proj12)
      _ <- aclDsl.addPermission(s"/$ref12", Alice, Resources.Read)
    } yield ()

    val resourcePayload = SimpleResource.sourcePayload(5)

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

    def multiFetchRequest(payload: Json, identity: Identity)(check: (Json, HttpResponse) => Assertion) = {
      deltaClient.getWithBody[Json]("/multi-fetch/resources", payload, identity) { check }
      deltaClient.post[Json]("/multi-fetch/resources", payload, identity) { check }
    }

    "get all resources for a user with all access" in {
      val expected = jsonContentOf(
        "/kg/multi-fetch/all-success.json",
        "project1" -> ref11,
        "project2" -> ref12
      )

      multiFetchRequest(request("source"), Bob) { (json, response) =>
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

      multiFetchRequest(request("source"), Alice) { (json, response) =>
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

      multiFetchRequest(request, Bob) { (json, response) =>
        response.status shouldEqual StatusCodes.OK
        json shouldEqual expected
      }
    }
  }

}
