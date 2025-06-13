package ai.senscience.nexus.delta.routes

import ai.senscience.nexus.delta.config.DescriptionConfig
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Route
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.dependency.ComponentDescription.{PluginDescription, ServiceDescription}
import ch.epfl.bluebrain.nexus.delta.kernel.dependency.ServiceDependency
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclSimpleCheck
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.identities.IdentitiesDummy
import ch.epfl.bluebrain.nexus.delta.sdk.model.Name
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions.version
import ch.epfl.bluebrain.nexus.delta.sdk.utils.BaseRouteSpec

class VersionRoutesSpec extends BaseRouteSpec {

  private val identities = IdentitiesDummy.fromUsers(alice)

  private val pluginsInfo = List(PluginDescription("pluginA", "1.0"), PluginDescription("pluginB", "2.0"))

  private val dependency1 = new ServiceDependency {
    override def serviceDescription: IO[ServiceDescription] = IO.pure(ServiceDescription.unresolved("elasticsearch"))
  }

  private val dependency2 = new ServiceDependency {
    override def serviceDescription: IO[ServiceDescription] = IO.pure(ServiceDescription("blazegraph", "1.0.0"))
  }

  private val descriptionConfig = DescriptionConfig(Name.unsafe("delta"), Name.unsafe("dev"))

  private val aclCheck = AclSimpleCheck.unsafe(
    (alice, AclAddress.Root, Set(version.read))
  )

  private lazy val routes = Route.seal(
    VersionRoutes(
      identities,
      aclCheck,
      pluginsInfo,
      List(dependency1, dependency2),
      descriptionConfig
    ).routes
  )

  "The version route" should {

    "return a default value without version/read permission" in {
      Get("/v1/version") ~> routes ~> check {
        val expected =
          json"""
            {
              "@context" : "https://bluebrain.github.io/nexus/contexts/version.json",
              "delta" : "unknown",
              "dependencies" : {

              },
              "environment" : "unknown",
              "plugins" : {

              }
            }
              """
        response.status shouldEqual StatusCodes.OK
        response.asJson shouldEqual expected
      }
    }

    "fetch plugins information" in {
      val expected =
        json"""
          {
            "@context": "https://bluebrain.github.io/nexus/contexts/version.json",
            "delta": "${descriptionConfig.version}",
            "dependencies": {
              "elasticsearch": "unknown",
              "blazegraph": "1.0.0"
            },
            "plugins": {
              "pluginA": "1.0",
              "pluginB": "2.0"
            },
            "environment": "dev"
          } """

      Get("/v1/version") ~> as(alice) ~> routes ~> check {
        response.status shouldEqual StatusCodes.OK
        response.asJson shouldEqual expected
      }
    }

  }
}
