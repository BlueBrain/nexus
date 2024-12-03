package ch.epfl.bluebrain.nexus.delta.routes

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.OAuth2BearerToken
import akka.http.scaladsl.server.Route
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.config.DescriptionConfig
import ch.epfl.bluebrain.nexus.delta.kernel.dependency.ComponentDescription.{PluginDescription, ServiceDescription}
import ch.epfl.bluebrain.nexus.delta.kernel.dependency.ServiceDependency
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclSimpleCheck
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.identities.IdentitiesDummy
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.Name
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions.version
import ch.epfl.bluebrain.nexus.delta.sdk.utils.BaseRouteSpec
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Anonymous, Authenticated, Group}

class VersionRoutesSpec extends BaseRouteSpec {

  private val caller = Caller(alice, Set(alice, Anonymous, Authenticated(realm), Group("group", realm)))

  private val identities = IdentitiesDummy(caller)

  private val asAlice = addCredentials(OAuth2BearerToken("alice"))

  private val pluginsInfo = List(PluginDescription("pluginA", "1.0"), PluginDescription("pluginB", "2.0"))

  private val dependency1 = new ServiceDependency {
    override def serviceDescription: IO[ServiceDescription] = IO.pure(ServiceDescription.unresolved("elasticsearch"))
  }

  private val dependency2 = new ServiceDependency {
    override def serviceDescription: IO[ServiceDescription] = IO.pure(ServiceDescription("blazegraph", "1.0.0"))
  }

  private val descriptionConfig = DescriptionConfig(Name.unsafe("delta"), Name.unsafe("dev"))

  private val aclCheck = AclSimpleCheck(
    (caller.subject, AclAddress.Root, Set(version.read))
  ).accepted

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
      aclCheck.append(AclAddress.Root, Anonymous -> Set(version.read), caller.subject -> Set(version.read))
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

      Get("/v1/version") ~> asAlice ~> routes ~> check {
        response.status shouldEqual StatusCodes.OK
        response.asJson shouldEqual expected
      }
    }

  }
}
