package ch.epfl.bluebrain.nexus.tests.kg

import akka.http.scaladsl.model.MediaTypes.`text/html`
import akka.http.scaladsl.model.headers.{Accept, Location}
import akka.http.scaladsl.model.{HttpResponse, MediaRange, StatusCodes}
import akka.http.scaladsl.unmarshalling.PredefinedFromEntityUnmarshallers
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UrlUtils
import ch.epfl.bluebrain.nexus.tests.BaseSpec
import ch.epfl.bluebrain.nexus.tests.Identity.listings.{Alice, Bob}
import ch.epfl.bluebrain.nexus.tests.iam.types.Permission.Organizations
import io.circe.Json

class IdResolutionSpec extends BaseSpec {

  private val org1   = genId()
  private val proj11 = genId()
  private val proj12 = genId()
  private val ref11  = s"$org1/$proj11"
  private val ref12  = s"$org1/$proj12"

  private def resource(id: String)  =
    json"""
      {
        "@context": {
          "@vocab": "https://bluebrain.github.io/nexus/vocabulary/"
        },
        "@id": "$id",
        "field": "value"
      }
        """
  private val uniqueId              = "https://bbp.epfl.ch/neuron"
  private val encodedUniqueId       = UrlUtils.encode(uniqueId)
  private val reusedId              = "https://bbp.epfl.ch/synapse"
  private val encodedReusedId       = UrlUtils.encode(reusedId)
  private val uniqueResourcePayload = resource(uniqueId)
  private val reusedResourcePayload = resource(reusedId)

  private val unauthorizedAccessErrorPayload =
    jsonContentOf("iam/errors/unauthorized-access.json")

  override def beforeAll(): Unit = {
    super.beforeAll()

    val setup = for {
      _ <- aclDsl.addPermission("/", Bob, Organizations.Create)
      _ <- adminDsl.createOrganization(org1, org1, Bob)
      _ <- adminDsl.createProject(org1, proj11, kgDsl.projectJson(name = proj11), Bob)
      _ <- adminDsl.createProject(org1, proj12, kgDsl.projectJson(name = proj12), Bob)
    } yield ()

    val createResources = for {
      _ <- deltaClient.post[Json](s"/resources/$ref11/_/", uniqueResourcePayload, Bob)(expectCreated)
      _ <- deltaClient.post[Json](s"/resources/$ref11/_/", reusedResourcePayload, Bob)(expectCreated)
      _ <- deltaClient.post[Json](s"/resources/$ref12/_/", reusedResourcePayload, Bob)(expectCreated)
    } yield ()

    (setup >> createResources).accepted
  }

  "Id resolution" should {

    "lead to an authorization failure for a user without permission" in {
      deltaClient.get[Json](s"/resolve/$encodedUniqueId", Alice) { (json, response) =>
        response.status shouldEqual StatusCodes.Forbidden
        json shouldEqual unauthorizedAccessErrorPayload
      }
    }

    "lead to an authorization failure when trying to resolve a resource that does not exist" in {
      deltaClient.get[Json](s"/resolve/unknownId", Bob) { (json, response) =>
        response.status shouldEqual StatusCodes.Forbidden
        json shouldEqual unauthorizedAccessErrorPayload
      }
    }

    "resolve a single resource" in {
      eventually {
        deltaClient.get[Json](s"/resolve/$encodedUniqueId", Bob) { (json, response) =>
          response.status shouldEqual StatusCodes.OK
          json shouldEqual uniqueResourcePayload
        }
      }
    }

    "return search results if the same id exists across several projects" in {
      eventually {
        deltaClient.get[Json](s"/resolve/$encodedReusedId", Bob) { (json, response) =>
          response.status shouldEqual StatusCodes.OK
          json.hcursor.get[Int]("_total") shouldEqual Right(2)
        }
      }
    }

    "redirect to fusion login when if text/html accept header is present (no results)" in {
      val expectedRedirectUrl = "https://bbp.epfl.ch/nexus/web/login"

      deltaClient.get[String]("/resolve/unknownId", Bob, acceptTextHtml) { (_, response) =>
        response.status shouldEqual StatusCodes.SeeOther
        locationHeaderOf(response) shouldEqual expectedRedirectUrl
      }(PredefinedFromEntityUnmarshallers.stringUnmarshaller)
    }

    "redirect to fusion resource page if text/html accept header is present (single result)" in {
      deltaClient.get[String](s"/resolve/$encodedUniqueId", Bob, acceptTextHtml) { (_, response) =>
        response.status shouldEqual StatusCodes.SeeOther
        locationHeaderOf(response) shouldEqual fusionResourcePageFor(encodedUniqueId)
      }(PredefinedFromEntityUnmarshallers.stringUnmarshaller)
    }

    "redirect to fusion resource selection page if text/html accept header is present (multiple result)" in { pending }

  }

  private def locationHeaderOf(response: HttpResponse) =
    response.header[Location].value.uri.toString()
  private def acceptTextHtml                           =
    List(Accept(MediaRange.One(`text/html`, 1f)))
  private def fusionResourcePageFor(encodedId: String) =
    s"https://bbp.epfl.ch/nexus/web/$ref11/resources/$encodedId".replace("%3A", ":")

}
