package ch.epfl.bluebrain.nexus.delta.routes

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.OAuth2BearerToken
import akka.http.scaladsl.server.Route
import ch.epfl.bluebrain.nexus.delta.sdk.Permissions.{events, plugins}
import ch.epfl.bluebrain.nexus.delta.sdk.model.Name
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.{Acl, AclAddress}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.{Anonymous, Authenticated, Group, Subject}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.{AuthToken, Caller, Identity}
import ch.epfl.bluebrain.nexus.delta.sdk.plugin.PluginInfo
import ch.epfl.bluebrain.nexus.delta.sdk.testkit._
import ch.epfl.bluebrain.nexus.delta.sdk.utils.RouteHelpers
import ch.epfl.bluebrain.nexus.delta.utils.RouteFixtures
import ch.epfl.bluebrain.nexus.testkit._
import org.scalatest.matchers.should.Matchers

class PluginsInfoRoutesSpec extends RouteHelpers with Matchers with IOValues with TestMatchers with RouteFixtures {

  implicit private val subject: Subject = Identity.Anonymous

  private val caller = Caller(alice, Set(alice, Anonymous, Authenticated(realm), Group("group", realm)))

  private val identities = IdentitiesDummy(Map(AuthToken("alice") -> caller))

  private val asAlice = addCredentials(OAuth2BearerToken("alice"))

  private val acls = AclsDummy(PermissionsDummy(Set(plugins.read, events.read))).accepted

  private val pluginsInfo = List(PluginInfo(Name.unsafe("pluginA"), "1.0"), PluginInfo(Name.unsafe("pluginB"), "2.0"))

  private val routes = Route.seal(PluginsInfoRoutes(identities, acls, pluginsInfo))

  "A plugins info route" should {

    "fail fetching plugins information without plugins/read permission" in {
      acls.append(Acl(AclAddress.Root, Anonymous -> Set(events.read)), 0).accepted
      Get("/v1/plugins") ~> routes ~> check {
        response.status shouldEqual StatusCodes.Forbidden
        response.asJson shouldEqual jsonContentOf("errors/authorization-failed.json")
      }
    }

    "fetch plugins information" in {
      acls.append(Acl(AclAddress.Root, Anonymous -> Set(plugins.read), caller.subject -> Set(plugins.read)), 1).accepted
      Get("/v1/plugins") ~> routes ~> check {
        response.status shouldEqual StatusCodes.OK
        response.asJson shouldEqual jsonContentOf("plugins/plugins-info-route-response.json")
      }

      Get("/v1/plugins") ~> asAlice ~> routes ~> check {
        response.status shouldEqual StatusCodes.OK
        response.asJson shouldEqual jsonContentOf("plugins/plugins-info-route-response.json")
      }
    }

  }
}
