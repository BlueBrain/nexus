package ai.senscience.nexus.tests.kg

import ai.senscience.nexus.tests.BaseIntegrationSpec
import ai.senscience.nexus.tests.Identity.listings.{Alice, Bob}
import ai.senscience.nexus.tests.iam.types.Permission.Organizations
import akka.http.scaladsl.model.MediaTypes.`text/html`
import akka.http.scaladsl.model.headers.{Accept, Location}
import akka.http.scaladsl.model.{HttpResponse, MediaRange, StatusCodes}
import akka.http.scaladsl.unmarshalling.PredefinedFromEntityUnmarshallers
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UrlUtils.encodeUriPath
import io.circe.{Decoder, Json}
import org.scalatest.Assertion

class IdResolutionSpec extends BaseIntegrationSpec {

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
  private val encodedUniqueId       = encodeUriPath(uniqueId)
  private val reusedId              = "https://bbp.epfl.ch/synapse"
  private val encodedReusedId       = encodeUriPath(reusedId)
  private val uniqueResourcePayload = resource(uniqueId)
  private val reusedResourcePayload = resource(reusedId)

  private val neurosciencegraphSegment   = "neurosciencegraph/data/segment"
  private val proxyIdBase                = "http://localhost:8081"
  private val neurosciencegraphId        = s"$proxyIdBase/$neurosciencegraphSegment"
  private val encodedNeurosciencegraphId = encodeUriPath(neurosciencegraphId)

  override def beforeAll(): Unit = {
    super.beforeAll()

    val setup = for {
      _ <- aclDsl.addPermission("/", Bob, Organizations.Create)
      _ <- adminDsl.createOrganization(org1, org1, Bob)
      _ <- adminDsl.createProjectWithName(org1, proj11, name = proj11, Bob)
      _ <- adminDsl.createProjectWithName(org1, proj12, name = proj12, Bob)
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
      deltaClient.get[Json](s"/resolve/$encodedUniqueId", Alice) { expectForbidden }
    }

    "lead to an authorization failure when trying to resolve a resource that does not exist" in {
      deltaClient.get[Json](s"/resolve/unknownId", Bob) { expectForbidden }
    }

    "resolve a single resource" in {
      eventually {
        deltaClient.get[Json](s"/resolve/$encodedUniqueId", Bob) { (json, response) =>
          response.status shouldEqual StatusCodes.OK
          json.topLevelField[String]("@id") shouldEqual uniqueId
          json.topLevelField[String]("_project") shouldEqual ref11
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

    "redirect to delta resolve if the request comes to the proxy endpoint" in {
      deltaClient.get[String](s"/resolve-proxy-pass/$neurosciencegraphSegment", Bob) { (_, response) =>
        response isRedirectTo deltaResolveEndpoint(encodedNeurosciencegraphId)
      }(PredefinedFromEntityUnmarshallers.stringUnmarshaller)
    }

    "redirect to fusion resolve if the request comes to the proxy endpoint with text/html accept header is present" in {
      deltaClient.get[String](s"/resolve-proxy-pass/$neurosciencegraphSegment", Bob, acceptTextHtml) { (_, response) =>
        response isRedirectTo fusionResolveEndpoint(encodedNeurosciencegraphId)
      }(PredefinedFromEntityUnmarshallers.stringUnmarshaller)
    }

  }

  implicit private class HttpResponseOps(response: HttpResponse) {
    def isRedirectTo(uri: String): Assertion = {
      response.status shouldEqual StatusCodes.SeeOther
      locationHeaderOf(response) shouldEqual uri
    }
  }

  implicit private class JsonOps(json: Json) {
    def topLevelField[A: Decoder](field: String): A =
      json.hcursor.get[A](field).toOption.get
  }

  private def locationHeaderOf(response: HttpResponse) =
    response.header[Location].value.uri.toString()
  private def acceptTextHtml                           =
    List(Accept(MediaRange.One(`text/html`, 1f)))
  private def fusionResolveEndpoint(encodedId: String) =
    s"https://bbp.epfl.ch/nexus/web/resolve/$encodedId".replace("%3A", ":")
  private def deltaResolveEndpoint(encodedId: String)  =
    s"http://delta:8080/v1/resolve/$encodedId".replace("%3A", ":")

}
