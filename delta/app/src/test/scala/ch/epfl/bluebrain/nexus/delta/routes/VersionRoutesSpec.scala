package ch.epfl.bluebrain.nexus.delta.routes

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.OAuth2BearerToken
import akka.http.scaladsl.server.Route
import ch.epfl.bluebrain.nexus.delta.config.DescriptionConfig
import ch.epfl.bluebrain.nexus.delta.sdk.Permissions.{events, version}
import ch.epfl.bluebrain.nexus.delta.sdk.ServiceDependency
import ch.epfl.bluebrain.nexus.delta.sdk.model.ComponentDescription.{PluginDescription, ServiceDescription}
import ch.epfl.bluebrain.nexus.delta.sdk.model.Name
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.{Acl, AclAddress}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Anonymous, Authenticated, Group, Subject}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.{AuthToken, Caller}
import ch.epfl.bluebrain.nexus.delta.sdk.testkit._
import ch.epfl.bluebrain.nexus.delta.sdk.utils.RouteHelpers
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity
import ch.epfl.bluebrain.nexus.delta.utils.RouteFixtures
import ch.epfl.bluebrain.nexus.testkit._
import monix.bio.UIO
import org.scalatest.matchers.should.Matchers

class VersionRoutesSpec extends RouteHelpers with Matchers with IOValues with TestMatchers with RouteFixtures {

  implicit private val subject: Subject = Identity.Anonymous

  private val caller = Caller(alice, Set(alice, Anonymous, Authenticated(realm), Group("group", realm)))

  private val identities = IdentitiesDummy(Map(AuthToken("alice") -> caller))

  private val asAlice = addCredentials(OAuth2BearerToken("alice"))

  private val acls = AclSetup.init(Set(version.read, events.read), Set(realm)).accepted

  private val pluginsInfo =
    List(PluginDescription(Name.unsafe("pluginA"), "1.0"), PluginDescription(Name.unsafe("pluginB"), "2.0"))

  private val dependency1 = new ServiceDependency {
    override def serviceDescription: UIO[ServiceDescription] =
      UIO.pure(ServiceDescription.unresolved(Name.unsafe("elasticsearch")))
  }

  private val dependency2 = new ServiceDependency {
    override def serviceDescription: UIO[ServiceDescription] =
      UIO.pure(ServiceDescription(Name.unsafe("remoteStorage"), "1.0.0"))
  }

  private val descriptionConfig = DescriptionConfig(Name.unsafe("delta"))

  private val routes = Route.seal(
    VersionRoutes(
      identities,
      acls,
      pluginsInfo,
      Set(dependency1, dependency2),
      descriptionConfig
    ).routes
  )

  "The version route" should {

    "fail fetching plugins information without version/read permission" in {
      acls.append(Acl(AclAddress.Root, Anonymous -> Set(events.read)), 0).accepted
      Get("/v1/version") ~> routes ~> check {
        response.status shouldEqual StatusCodes.Forbidden
        response.asJson shouldEqual jsonContentOf("errors/authorization-failed.json")
      }
    }

    "fetch plugins information" in {
      acls.append(Acl(AclAddress.Root, Anonymous -> Set(version.read), caller.subject -> Set(version.read)), 1).accepted
      val expected = jsonContentOf("version-response.json", "version" -> descriptionConfig.version)
      Get("/v1/version") ~> routes ~> check {
        response.status shouldEqual StatusCodes.OK
        response.asJson shouldEqual expected
      }

      Get("/v1/version") ~> asAlice ~> routes ~> check {
        response.status shouldEqual StatusCodes.OK
        response.asJson shouldEqual expected
      }
    }

  }
}
