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

  private def resource(id: String) =
    json"""
      {
        "@context": {
          "@vocab": "https://bluebrain.github.io/nexus/vocabulary/"
        },
        "@id": "$id",
        "field": "value"
      }
        """
  private val resourceId           = "https://bbp.epfl.ch/neuron"
  private val resourceId2          = "https://bbp.epfl.ch/synapse"
  private val resourcePayload1     = resource(resourceId)
  private val resourcePayload2     = resource(resourceId2)

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

    println(resourcePayload1)

    val createResources = for {
      _ <- deltaClient.post[Json](s"/resources/$ref11/_/", resourcePayload1, Bob)(expectCreated)
      _ <- deltaClient.post[Json](s"/resources/$ref11/_/", resourcePayload2, Bob)(expectCreated)
      _ <- deltaClient.post[Json](s"/resources/$ref12/_/", resourcePayload2, Bob)(expectCreated)
    } yield ()

    (setup >> createResources).accepted
  }

  "Id resolution" should {
    val iri = UrlUtils.encode("https://bluebrain.github.io/nexus/vocabulary/resource")

    "lead to an authorization failure for a user without permission" in {
      deltaClient.get[Json](s"/resolve/$iri", Alice) { (json, response) =>
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
        deltaClient.get[Json](s"/resolve/${UrlUtils.encode(resourceId)}", Bob) { (json, response) =>
          response.status shouldEqual StatusCodes.OK
          json shouldEqual resourcePayload1
        }
      }
    }

    "return search results if the same id exists across several projects" in {
      eventually {
        deltaClient.get[Json](s"/resolve/${UrlUtils.encode(resourceId2)}", Bob) { (json, response) =>
          response.status shouldEqual StatusCodes.OK
          json.hcursor.get[Int]("_total") shouldEqual Right(2)
        }
      }
    }

    "redirect to fusion error when if text/html header is present (no results)" in { pending }

    "redirect to fusion resource page if header is present (single result)" in {
      deltaClient.get[String](
        s"/resolve/${UrlUtils.encode(resourceId)}",
        Bob,
        extraHeaders = List(Accept(MediaRange.One(`text/html`, 1f)))
      ) { (_, response) =>
        response.status shouldEqual StatusCodes.SeeOther
        locationHeaderOf(response) shouldEqual s"https://bbp.epfl.ch/nexus/web/$ref11/resources/${UrlUtils.encode(resourceId)}"
      }(PredefinedFromEntityUnmarshallers.stringUnmarshaller)
    }

    "redirect to fusion resource selection page if header is present (multiple result)" in { pending }

  }

  private def locationHeaderOf(response: HttpResponse) =
    response.header[Location].value.uri.toString()
}
