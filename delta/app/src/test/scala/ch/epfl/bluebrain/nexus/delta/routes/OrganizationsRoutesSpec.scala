package ch.epfl.bluebrain.nexus.delta.routes

import java.util.UUID

import akka.http.scaladsl.model.MediaRanges.`*/*`
import akka.http.scaladsl.model.MediaTypes.`text/event-stream`
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.{`Last-Event-ID`, Accept, OAuth2BearerToken}
import akka.http.scaladsl.server.Route
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UrlUtils
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.sdk.generators.OrganizationGen
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.{Acl, AclAddress}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.{Anonymous, Authenticated, Group, Subject}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.{AuthToken, Caller, Identity}
import ch.epfl.bluebrain.nexus.delta.sdk.Permissions.{events, orgs => orgsPermissions}
import ch.epfl.bluebrain.nexus.delta.sdk.model.Label
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.{AclsDummy, ApplyOwnerPermissionsDummy, IdentitiesDummy, OrganizationsDummy, PermissionsDummy}
import ch.epfl.bluebrain.nexus.delta.sdk.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.utils.{RouteFixtures, RouteHelpers}
import ch.epfl.bluebrain.nexus.testkit._
import io.circe.Json
import org.scalatest.matchers.should.Matchers
import org.scalatest.{Inspectors, OptionValues}

class OrganizationsRoutesSpec
    extends RouteHelpers
    with Matchers
    with CirceLiteral
    with CirceEq
    with DeltaDirectives
    with IOFixedClock
    with IOValues
    with OptionValues
    with TestMatchers
    with Inspectors
    with RouteFixtures {

  private val fixedUuid             = UUID.randomUUID()
  implicit private val uuidF: UUIDF = UUIDF.fixed(fixedUuid)

  private val org1 = OrganizationGen.organization("org1", fixedUuid, Some("My description"))
  private val org2 = OrganizationGen.organization("org2", fixedUuid)

  implicit private val subject: Subject = Identity.Anonymous

  private val acls = AclsDummy(PermissionsDummy(Set(orgsPermissions.write, orgsPermissions.read, events.read))).accepted
  private val aopd = ApplyOwnerPermissionsDummy(acls, Set(orgsPermissions.write, orgsPermissions.read), subject)
  private val orgs = OrganizationsDummy(aopd).accepted

  private val caller = Caller(alice, Set(alice, Anonymous, Authenticated(realm), Group("group", realm)))

  private val identities = IdentitiesDummy(Map(AuthToken("alice") -> caller))

  private val routes = Route.seal(OrganizationsRoutes(identities, orgs, acls))

  private val org1CreatedMeta = orgResourceUnit(org1.label)

  private val org1Created = jsonContentOf(
    "/organizations/org-resource.json",
    "label"       -> org1.label.value,
    "uuid"        -> fixedUuid.toString,
    "description" -> org1.description.value
  ) deepMerge org1CreatedMeta.removeKeys("@context")

  private val org1UpdatedMeta = orgResourceUnit(org1.label, rev = 2L)
  private val org1Updated     =
    org1Created deepMerge json"""{"description": "updated"}""" deepMerge org1UpdatedMeta.removeKeys("@context")

  private val org2CreatedMeta = orgResourceUnit(org2.label, createdBy = alice, updatedBy = alice)

  private val org2Created = jsonContentOf(
    "/organizations/org-resource.json",
    "label" -> org2.label.value,
    "uuid"  -> fixedUuid.toString
  ).removeKeys("description") deepMerge org2CreatedMeta.removeKeys("@context")

  private val org2DeprecatedMeta =
    orgResourceUnit(org2.label, rev = 2L, deprecated = true, createdBy = alice, updatedBy = alice)

  "An OrganizationsRoute" should {

    "fail to create an organization without organizations/write permission" in {
      val input = json"""{"description": "${org1.description.value}"}"""

      Put("/v1/orgs/org1", input.toEntity) ~> routes ~> check {
        response.status shouldEqual StatusCodes.Forbidden
        response.asJson shouldEqual jsonContentOf("errors/authorization-failed.json")
      }
    }

    "create a new organization" in {
      acls
        .append(
          Acl(AclAddress.Root, Anonymous -> Set(orgsPermissions.write), caller.subject -> Set(orgsPermissions.write)),
          0L
        )
        .accepted
      val input = json"""{"description": "${org1.description.value}"}"""

      Put("/v1/orgs/org1", input.toEntity) ~> routes ~> check {
        status shouldEqual StatusCodes.Created
        response.asJson shouldEqual org1CreatedMeta
      }
    }

    "create another organization with an authenticated user" in {
      Put("/v1/orgs/org2", Json.obj().toEntity) ~> addCredentials(OAuth2BearerToken("alice")) ~> routes ~> check {
        status shouldEqual StatusCodes.Created
        response.asJson shouldEqual org2CreatedMeta
      }
    }

    "update an existing organization" in {
      val input = json"""{"description": "updated"}"""

      Put("/v1/orgs/org1?rev=1", input.toEntity) ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson shouldEqual org1UpdatedMeta
      }
    }

    "fetch an organization by label" in {
      Get("/v1/orgs/org1") ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson shouldEqual org1Updated
      }
    }

    "fetch an organization by UUID" in {
      Get(s"/v1/orgs/$fixedUuid") ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson shouldEqual org1Updated
      }
    }

    "fetch an organization by label and rev" in {
      Get("/v1/orgs/org1?rev=1") ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson shouldEqual org1Created
      }
    }

    "fetch an organization by UUID and rev" in {
      Get(s"/v1/orgs/$fixedUuid?rev=1") ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson shouldEqual org1Created
      }
    }

    "reject the creation of a organization if it already exists" in {
      val input = json"""{"description": "${org1.description.value}"}"""

      Put("/v1/orgs/org1", input.toEntity) ~> routes ~> check {
        status shouldEqual StatusCodes.Conflict
        response.asJson shouldEqual jsonContentOf("/organizations/already-exists.json", "org" -> org1.label.value)
      }
    }

    "fail fetching an organization by label and rev when rev is invalid" in {
      Get("/v1/orgs/org1?rev=4") ~> routes ~> check {
        status shouldEqual StatusCodes.NotFound
        response.asJson shouldEqual jsonContentOf("/errors/revision-not-found.json", "provided" -> 4, "current" -> 2)
      }
    }

    def expectedResults(results: Json*): Json =
      json"""{"@context": ["${contexts.resource}", "${contexts.organizations}", "${contexts.search}"], "_total": ${results.size}}""" deepMerge
        Json.obj("_results" -> Json.arr(results: _*))

    "list organizations" in {
      Get("/v1/orgs") ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson should equalIgnoreArrayOrder(
          expectedResults(
            org1Updated.removeKeys("@context"),
            org2Created.removeKeys("@context")
          )
        )
      }
    }

    "list organizations with revision 2" in {
      Get("/v1/orgs?rev=2") ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson should equalIgnoreArrayOrder(expectedResults(org1Updated.removeKeys("@context")))
      }
    }

    "list organizations created by alice" in {
      Get(s"/v1/orgs?createdBy=${UrlUtils.encode(alice.id.toString)}") ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson should equalIgnoreArrayOrder(expectedResults(org2Created.removeKeys("@context")))
      }
    }

    "deprecate an organization" in {
      Delete("/v1/orgs/org2?rev=1") ~> addCredentials(OAuth2BearerToken("alice")) ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson should equalIgnoreArrayOrder(org2DeprecatedMeta)
      }
    }

    "fail fetch an organization by label without organizations/read permission" in {
      acls.delete(AclAddress.Root, 1L).accepted
      Get("/v1/orgs/org2") ~> routes ~> check {
        response.asJson shouldEqual jsonContentOf("errors/authorization-failed.json")
        response.status shouldEqual StatusCodes.Forbidden
      }
    }

    "fail to fetch an organization by UUID without orgs/read permission" in {
      acls.delete(AclAddress.Organization(Label.unsafe("org1")), 1L).accepted
      Get(s"/v1/orgs/$fixedUuid") ~> routes ~> check {
        response.status shouldEqual StatusCodes.Forbidden
        response.asJson shouldEqual jsonContentOf("errors/authorization-failed.json")
      }
    }

    "fail to fetch an organization by UUID and rev without orgs/read permission" in {
      Get(s"/v1/orgs/$fixedUuid?rev=1") ~> routes ~> check {
        response.status shouldEqual StatusCodes.Forbidden
        response.asJson shouldEqual jsonContentOf("errors/authorization-failed.json")
      }
    }

    "fail to get the events stream without events/read permission" in {
      Get("/v1/orgs/events") ~> Accept(`*/*`) ~> `Last-Event-ID`("2") ~> routes ~> check {
        response.asJson shouldEqual jsonContentOf("errors/authorization-failed.json")
        response.status shouldEqual StatusCodes.Forbidden
      }
    }

    "get the events stream with an offset" in {
      acls.append(Acl(AclAddress.Root, Anonymous -> Set(events.read)), 2L).accepted
      Get("/v1/orgs/events") ~> Accept(`*/*`) ~> `Last-Event-ID`("2") ~> routes ~> check {
        mediaType shouldBe `text/event-stream`
        response.asString shouldEqual contentOf("/organizations/eventstream-2-4.txt", "uuid" -> fixedUuid.toString)
      }
    }
  }
}
