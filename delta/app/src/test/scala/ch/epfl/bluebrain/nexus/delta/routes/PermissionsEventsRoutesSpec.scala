package ch.epfl.bluebrain.nexus.delta.routes

import akka.http.scaladsl.model.MediaRanges.`*/*`
import akka.http.scaladsl.model.MediaTypes.`text/event-stream`
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.{`Last-Event-ID`, Accept}
import akka.http.scaladsl.server.Route
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.{Acl, AclAddress}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.{AuthToken, Caller}
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions.{acls, events, realms, resources}
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.{Permissions, PermissionsConfig, PermissionsImpl}
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.{AclsDummy, IdentitiesDummy, RealmSetup}
import ch.epfl.bluebrain.nexus.delta.sdk.utils.RouteHelpers
import ch.epfl.bluebrain.nexus.delta.sourcing.config.{EventLogConfig, QueryConfig}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Anonymous, Subject}
import ch.epfl.bluebrain.nexus.delta.sourcing.query.RefreshStrategy
import ch.epfl.bluebrain.nexus.delta.utils.RouteFixtures
import ch.epfl.bluebrain.nexus.testkit._
import org.scalatest.Inspectors
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._

class PermissionsEventsRoutesSpec
    extends RouteHelpers
    with DoobieFixture
    with Matchers
    with CirceLiteral
    with IOFixedClock
    with IOValues
    with TestMatchers
    with Inspectors
    with RouteFixtures {

  implicit private val caller: Subject = Identity.Anonymous

  private val identities = IdentitiesDummy(Map.empty[AuthToken, Caller])

  private val eventLogConfig = EventLogConfig(QueryConfig(10, RefreshStrategy.Stop), 100.millis)

  private val config = PermissionsConfig(
    eventLogConfig,
    Set(Permissions.events.read, resources.read),
    Set.empty
  )

  lazy val (permissions, aclsDummy) = (for {
    realms <- RealmSetup.init(realm)
    perms   = PermissionsImpl(config, xas)
    acls   <- AclsDummy(perms, realms)
  } yield (perms, acls)).accepted
  private lazy val route            = Route.seal(PermissionsRoutes(identities, permissions, aclsDummy))

  "The permissions routes" should {

    "add some permissions" in {
      List(
        permissions.append(Set(acls.read), 0),
        permissions.subtract(Set(acls.read), 1),
        permissions.replace(Set(acls.write), 2),
        permissions.delete(3),
        permissions.append(Set(acls.read), 4),
        permissions.append(Set(realms.write), 5),
        permissions.subtract(Set(realms.write), 6),
        aclsDummy.append(Acl(AclAddress.Root, Anonymous -> Set(resources.read)), 0L)
      ).traverse(_.void).accepted
    }

    "fail to get the events stream without events/read permission" in {

      Get("/v1/permissions/events") ~> Accept(`*/*`) ~> route ~> check {
        response.asJson shouldEqual jsonContentOf("errors/authorization-failed.json")
        response.status shouldEqual StatusCodes.Forbidden
      }
    }

    "return the event stream when no offset is provided" in {
      aclsDummy.append(Acl(AclAddress.Root, Anonymous -> Set(events.read)), 1L).accepted
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
