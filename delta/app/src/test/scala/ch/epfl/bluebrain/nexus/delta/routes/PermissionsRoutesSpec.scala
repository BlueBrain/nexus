package ch.epfl.bluebrain.nexus.delta.routes

import akka.http.scaladsl.model.MediaRanges.`*/*`
import akka.http.scaladsl.model.MediaTypes.`text/event-stream`
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.{`Last-Event-ID`, Accept}
import akka.http.scaladsl.server.Route
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.RdfMediaTypes._
import ch.epfl.bluebrain.nexus.delta.sdk.Permissions.{acls, events, orgs, realms, permissions => permissionsPerms}
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.{Acl, AclAddress}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.{AuthToken, Caller, Identity}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.{Anonymous, Subject}
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.{AclsDummy, IdentitiesDummy, PermissionsDummy}
import ch.epfl.bluebrain.nexus.delta.sdk.utils.RouteHelpers
import ch.epfl.bluebrain.nexus.delta.utils.RouteFixtures
import ch.epfl.bluebrain.nexus.testkit._
import org.scalatest.Inspectors
import org.scalatest.matchers.should.Matchers

class PermissionsRoutesSpec
    extends RouteHelpers
    with Matchers
    with CirceLiteral
    with IOFixedClock
    with IOValues
    with TestMatchers
    with Inspectors
    with RouteFixtures {

  implicit private val caller: Subject = Identity.Anonymous

  private val minimum        = Set(acls.read, acls.write, permissionsPerms.read, permissionsPerms.write, events.read)
  private val identities     = IdentitiesDummy(Map.empty[AuthToken, Caller])
  private val permissionsUIO = PermissionsDummy(minimum)
  private val aclsDummy      = AclsDummy(permissionsUIO).accepted
  private val permissions    = permissionsUIO.accepted
  private val route          = Route.seal(PermissionsRoutes(identities, permissions, aclsDummy))

  "The permissions routes" should {

    "fail to fetch permissions without permissions/read permission" in {
      aclsDummy.replace(Acl(AclAddress.Root, Anonymous -> Set(permissionsPerms.write)), 0L).accepted
      Get("/v1/permissions") ~> Accept(`*/*`) ~> route ~> check {
        response.status shouldEqual StatusCodes.Forbidden
        response.asJson shouldEqual jsonContentOf("errors/authorization-failed.json")
      }
    }

    "fetch permissions" in {
      aclsDummy.append(Acl(AclAddress.Root, Anonymous -> Set(permissionsPerms.read)), 1L).accepted
      val expected = jsonContentOf(
        "permissions/fetch_compacted.jsonld",
        "rev"         -> "0",
        "permissions" -> minimum.mkString("\"", "\",\"", "\"")
      )
      Get("/v1/permissions") ~> Accept(`*/*`) ~> route ~> check {
        response.asJson shouldEqual expected

        response.entity.contentType shouldEqual `application/ld+json`.toContentType
      }
    }

    "fetch permissions at specific revision" in {
      permissions.append(Set(realms.read), 0L).accepted

      val permissionsToFetch = minimum + realms.read
      val expected           = jsonContentOf(
        "permissions/fetch_compacted.jsonld",
        "rev"         -> "1",
        "permissions" -> permissionsToFetch.mkString("\"", "\",\"", "\"")
      )

      Get("/v1/permissions?rev=1") ~> Accept(`*/*`) ~> route ~> check {
        response.asJson shouldEqual expected
        response.status shouldEqual StatusCodes.OK
        response.entity.contentType shouldEqual `application/ld+json`.toContentType
      }
    }

    "fail to replace permissions without permissions/write permission" in {
      aclsDummy.subtract(Acl(AclAddress.Root, Anonymous -> Set(permissionsPerms.write)), 2L).accepted
      val replace = json"""{"permissions": ["${realms.write}"]}"""
      Put("/v1/permissions?rev=1", replace.toEntity) ~> Accept(`*/*`) ~> route ~> check {
        response.status shouldEqual StatusCodes.Forbidden
        response.asJson shouldEqual jsonContentOf("errors/authorization-failed.json")
      }
    }

    "fail to append permissions without permissions/write permission" in {
      val append = json"""{"@type": "Append", "permissions": ["${realms.read}", "${orgs.read}"]}"""
      Patch("/v1/permissions?rev=2", append.toEntity) ~> Accept(`*/*`) ~> route ~> check {
        response.status shouldEqual StatusCodes.Forbidden
        response.asJson shouldEqual jsonContentOf("errors/authorization-failed.json")
      }
    }

    "fail to subtract permissions without permissions/write permission" in {
      val subtract = json"""{"@type": "Subtract", "permissions": ["${realms.read}", "${realms.write}"]}"""
      Patch("/v1/permissions?rev=3", subtract.toEntity) ~> Accept(`*/*`) ~> route ~> check {
        response.status shouldEqual StatusCodes.Forbidden
        response.asJson shouldEqual jsonContentOf("errors/authorization-failed.json")
      }
    }

    "fail to delete permissions without permissions/write permission" in {
      Delete("/v1/permissions?rev=4") ~> Accept(`*/*`) ~> route ~> check {
        response.status shouldEqual StatusCodes.Forbidden
        response.asJson shouldEqual jsonContentOf("errors/authorization-failed.json")
      }
    }

    "replace permissions" in {

      aclsDummy.append(Acl(AclAddress.Root, Anonymous -> Set(permissionsPerms.write)), 3L).accepted
      val expected = permissionsMetadata(rev = 2L)
      val replace  = json"""{"permissions": ["${realms.write}"]}"""
      Put("/v1/permissions?rev=1", replace.toEntity) ~> Accept(`*/*`) ~> route ~> check {
        response.asJson shouldEqual expected
        response.status shouldEqual StatusCodes.OK
        response.entity.contentType shouldEqual `application/ld+json`.toContentType
      }
      permissions.fetchAt(2L).accepted.value.permissions shouldEqual minimum + realms.write
    }

    "append permissions" in {
      val expected = permissionsMetadata(rev = 3L)

      val append = json"""{"@type": "Append", "permissions": ["${realms.read}", "${orgs.read}"]}"""
      Patch("/v1/permissions?rev=2", append.toEntity) ~> Accept(`*/*`) ~> route ~> check {
        response.asJson shouldEqual expected
        response.status shouldEqual StatusCodes.OK
        response.entity.contentType shouldEqual `application/ld+json`.toContentType
      }
      permissions.fetchAt(3L).accepted.value.permissions shouldEqual
        minimum ++ Set(realms.write, realms.read, orgs.read)
    }

    "subtract permissions" in {
      val expected = permissionsMetadata(rev = 4L)

      val subtract = json"""{"@type": "Subtract", "permissions": ["${realms.read}", "${realms.write}"]}"""
      Patch("/v1/permissions?rev=3", subtract.toEntity) ~> Accept(`*/*`) ~> route ~> check {
        response.asJson shouldEqual expected
        response.status shouldEqual StatusCodes.OK
        response.entity.contentType shouldEqual `application/ld+json`.toContentType
      }
      permissions.fetchAt(4L).accepted.value.permissions shouldEqual minimum + orgs.read
    }

    "delete permissions" in {
      val expected = permissionsMetadata(rev = 5L)

      Delete("/v1/permissions?rev=4") ~> Accept(`*/*`) ~> route ~> check {
        response.asJson shouldEqual expected
        response.status shouldEqual StatusCodes.OK
        response.entity.contentType shouldEqual `application/ld+json`.toContentType
      }
      permissions.fetchAt(5L).accepted.value.permissions shouldEqual minimum
    }

    "reject on PATCH request with unknown @type" in {
      val wrongPatch = json"""{"@type": "Other", "permissions": ["${realms.read}"]}"""

      Patch("/v1/permissions?rev=5", wrongPatch.toEntity) ~> Accept(`*/*`) ~> route ~> check {
        response.asJson shouldEqual jsonContentOf("permissions/reject_malformed.jsonld")
        response.status shouldEqual StatusCodes.BadRequest
        response.entity.contentType shouldEqual `application/ld+json`.toContentType
      }
    }

    "reject on PATCH request with Replace @type" in {
      val wrongPatch = json"""{"@type": "Replace", "permissions": ["${realms.read}"]}"""

      Patch("/v1/permissions?rev=5", wrongPatch.toEntity) ~> Accept(`*/*`) ~> route ~> check {
        val errMsg   = s"Value for field '${keywords.tpe}' must be 'Append' or 'Subtract' when using 'PATCH'."
        val expected = jsonContentOf("permissions/reject_malformed.jsonld") deepMerge json"""{"details": "$errMsg"}"""
        response.asJson shouldEqual expected
        response.status shouldEqual StatusCodes.BadRequest
        response.entity.contentType shouldEqual `application/ld+json`.toContentType
      }
    }

    "reject on PUT request" in {
      val wrongReplace = json"""{"@type": "Other", "permissions": ["${realms.write}"]}"""
      val err          = s"Expected value 'Replace' when using 'PUT'."

      Put("/v1/permissions?rev=5", wrongReplace.toEntity) ~> Accept(`*/*`) ~> route ~> check {
        response.asJson shouldEqual jsonContentOf("permissions/reject_malformed.jsonld", "msg" -> err)
        response.status shouldEqual StatusCodes.BadRequest
        response.entity.contentType shouldEqual `application/ld+json`.toContentType
      }
    }

    "reject on wrong revision query param" in {
      val replace = json"""{"permissions": ["${realms.write}"]}"""
      Put("/v1/permissions?rev=6", replace.toEntity) ~> Accept(`*/*`) ~> route ~> check {
        response.asJson shouldEqual jsonContentOf(
          "permissions/reject_incorrect_rev.jsonld",
          "provided" -> "6",
          "expected" -> "5"
        )
        response.status shouldEqual StatusCodes.Conflict
        response.entity.contentType shouldEqual `application/ld+json`.toContentType
      }
    }

    "reject on non existing resource endpoint" in {
      Get("/v1/other") ~> Accept(`*/*`) ~> route ~> check {
        response.asJson shouldEqual jsonContentOf("permissions/reject_endpoint_not_found.jsonld")
        response.status shouldEqual StatusCodes.NotFound
        response.entity.contentType shouldEqual `application/ld+json`.toContentType
      }
    }

    "fail to get the events stream without events/read permission" in {
      Get("/v1/permissions/events") ~> Accept(`*/*`) ~> route ~> check {
        response.asJson shouldEqual jsonContentOf("errors/authorization-failed.json")
        response.status shouldEqual StatusCodes.Forbidden
      }
    }

    "return the event stream when no offset is provided" in {
      aclsDummy.append(Acl(AclAddress.Root, Anonymous -> Set(events.read)), 4L).accepted
      val dummy = PermissionsDummy(Set.empty, 5L).accepted
      val route = Route.seal(PermissionsRoutes(identities, dummy, aclsDummy))
      dummy.append(Set(acls.read), 0L).accepted
      dummy.subtract(Set(acls.read), 1L).accepted
      dummy.replace(Set(acls.write), 2L).accepted
      dummy.delete(3L).accepted
      dummy.append(Set(acls.read), 4L).accepted
      dummy.append(Set(realms.write), 5L).accepted
      dummy.subtract(Set(realms.write), 6L).accepted
      Get("/v1/permissions/events") ~> Accept(`*/*`) ~> route ~> check {
        mediaType shouldBe `text/event-stream`
        response.asString.strip shouldEqual contentOf("/permissions/eventstream-0-5.txt").strip
      }
    }

    "return the event stream when an offset is provided" in {
      val dummy = PermissionsDummy(Set.empty, 5L).accepted
      val route = Route.seal(PermissionsRoutes(identities, dummy, aclsDummy))
      dummy.append(Set(acls.read), 0L).accepted
      dummy.subtract(Set(acls.read), 1L).accepted
      dummy.replace(Set(acls.write), 2L).accepted
      dummy.delete(3L).accepted
      dummy.append(Set(acls.read), 4L).accepted
      dummy.append(Set(realms.write), 5L).accepted
      dummy.subtract(Set(realms.write), 6L).accepted
      Get("/v1/permissions/events") ~> Accept(`*/*`) ~> `Last-Event-ID`("2") ~> route ~> check {
        mediaType shouldBe `text/event-stream`
        response.asString.strip shouldEqual contentOf("/permissions/eventstream-2-7.txt").strip
      }
    }
  }

}
