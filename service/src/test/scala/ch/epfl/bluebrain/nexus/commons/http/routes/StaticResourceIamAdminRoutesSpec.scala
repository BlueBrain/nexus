package ch.epfl.bluebrain.nexus.commons.http.routes

import java.util.UUID

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.testkit.ScalatestRouteTest
import ch.epfl.bluebrain.nexus.commons.http.RdfMediaTypes
import org.scalatest.Inspectors
import java.util.regex.Pattern.quote

import ch.epfl.bluebrain.nexus.util.Resources
import com.typesafe.config.{Config, ConfigFactory}
import io.circe.parser.parse
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class StaticResourceIamAdminRoutesSpec
    extends AnyWordSpecLike
    with Matchers
    with Inspectors
    with ScalatestRouteTest
    with Resources {

  val baseUri = "http://nexus.example.com/v1"

  override def testConfig: Config = ConfigFactory.empty()

  val staticRoutes = new StaticResourceRoutes(
    Map(
      "/contexts/context1" -> "/commons/static-routes-test/contexts/context1.json",
      "/contexts/context2" -> "/commons/static-routes-test/contexts/context2.json",
      "/schemas/schema1"   -> "/commons/static-routes-test/schemas/schema1.json",
      "/schemas/schema2"   -> "/commons/static-routes-test/schemas/schema2.json"
    ),
    "test",
    baseUri
  ).routes

  val baseReplacement = Map(
    quote("{{base}}") -> baseUri
  )
  val files = Map(
    "/v1/test/contexts/context1" -> jsonContentOf(
      "/commons/static-routes-test/contexts/context1.json",
      baseReplacement
    ),
    "/v1/test/contexts/context2" -> jsonContentOf(
      "/commons/static-routes-test/contexts/context2.json",
      baseReplacement
    ),
    "/v1/test/schemas/schema1" -> jsonContentOf("/commons/static-routes-test/schemas/schema1.json", baseReplacement),
    "/v1/test/schemas/schema2" -> jsonContentOf("/commons/static-routes-test/schemas/schema2.json", baseReplacement)
  )

  "A StaticResourceRoutes" should {

    "return static resources" in {
      forAll(files.toList) {
        case (path, json) =>
          Get(path) ~> staticRoutes ~> check {
            status shouldEqual StatusCodes.OK
            contentType shouldEqual RdfMediaTypes.`application/ld+json`.toContentType
            parse(responseAs[String]).toOption.get shouldEqual json
          }
      }

    }

    "return 404 when resource doesn't exist" in {
      Get(s"/v1/test/schemas/${UUID.randomUUID().toString}") ~> staticRoutes ~> check {
        rejections shouldEqual Seq()
      }
    }
  }

}
