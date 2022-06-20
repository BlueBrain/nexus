package ch.epfl.bluebrain.nexus.delta.routes

import akka.http.scaladsl.model.MediaRanges.`*/*`
import akka.http.scaladsl.model.MediaTypes.`text/event-stream`
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.{`Last-Event-ID`, Accept}
import akka.http.scaladsl.server.Route
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclSimpleCheck
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.identities.IdentitiesDummy
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions.{events, realms, resources}
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.{Permissions, PermissionsConfig, PermissionsImpl}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Anonymous, Subject}

class PermissionsEventsRoutesSpec extends BaseRouteSpec {

  implicit private val caller: Subject = Identity.Anonymous

  private val identities = IdentitiesDummy()

  private val config = PermissionsConfig(
    eventLogConfig,
    Set(Permissions.events.read, resources.read),
    Set.empty
  )

  private val aclCheck         = AclSimpleCheck().accepted
  private lazy val permissions = PermissionsImpl(config, xas)
  private lazy val route       = Route.seal(PermissionsRoutes(identities, permissions, aclCheck))

  "The permissions routes" should {

    "add some permissions" in {
      List(
        permissions.append(Set(Permissions.acls.read), 0),
        permissions.subtract(Set(Permissions.acls.read), 1),
        permissions.replace(Set(Permissions.acls.write), 2),
        permissions.delete(3),
        permissions.append(Set(Permissions.acls.read), 4),
        permissions.append(Set(realms.write), 5),
        permissions.subtract(Set(realms.write), 6),
        aclCheck.append(AclAddress.Root, Anonymous -> Set(resources.read))
      ).traverse(_.void).accepted
    }

    "fail to get the events stream without events/read permission" in {

      Get("/v1/permissions/events") ~> Accept(`*/*`) ~> route ~> check {
        response.asJson shouldEqual jsonContentOf("errors/authorization-failed.json")
        response.status shouldEqual StatusCodes.Forbidden
      }
    }

    "return the event stream when no offset is provided" in {
      aclCheck.append(AclAddress.Root, Anonymous -> Set(events.read)).accepted
      Get("/v1/permissions/events") ~> Accept(`*/*`) ~> route ~> check {
        mediaType shouldBe `text/event-stream`
        //chunksStream.asString(5).strip shouldEqual contentOf("/permissions/eventstream-0-5.txt").strip
      }
    }

    "return the event stream when an offset is provided" in {
      Get("/v1/permissions/events") ~> Accept(`*/*`) ~> `Last-Event-ID`("2") ~> route ~> check {
        mediaType shouldBe `text/event-stream`
        chunksStream.asString(5).strip shouldEqual contentOf("/permissions/eventstream-2-7.txt").strip
      }
    }
  }

}
