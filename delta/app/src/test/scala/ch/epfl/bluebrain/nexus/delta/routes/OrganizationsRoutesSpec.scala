package ch.epfl.bluebrain.nexus.delta.routes

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.OAuth2BearerToken
import akka.http.scaladsl.server.Route
import ch.epfl.bluebrain.nexus.delta.kernel.effect.migration._
import ch.epfl.bluebrain.nexus.delta.kernel.utils.{UUIDF, UrlUtils}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclSimpleCheck
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaSchemeDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.generators.OrganizationGen
import ch.epfl.bluebrain.nexus.delta.sdk.identities.IdentitiesDummy
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.OrganizationsConfig
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.OrganizationDeleter
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.OrganizationsImpl
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.model.OrganizationRejection.OrganizationNonEmpty
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions.events
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions.{orgs => orgsPermissions}
import ch.epfl.bluebrain.nexus.delta.sdk.projects.OwnerPermissionsScopeInitialization
import ch.epfl.bluebrain.nexus.delta.sdk.utils.BaseRouteSpec
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Anonymous
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Authenticated
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Group
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.testkit.bio.IOFromMap
import io.circe.Json
import cats.effect.IO
import ch.epfl.bluebrain.nexus.testkit.ce.{CatsIOValues, IOFixedClock}

import java.util.UUID

class OrganizationsRoutesSpec extends BaseRouteSpec with IOFromMap with IOFixedClock with CatsIOValues {

  private val fixedUuid             = UUID.randomUUID()
  implicit private val uuidF: UUIDF = UUIDF.fixed(fixedUuid)

  private val org1 = OrganizationGen.organization("org1", fixedUuid, Some("My description"))
  private val org2 = OrganizationGen.organization("org2", fixedUuid)

  private val config = OrganizationsConfig(eventLogConfig, pagination, cacheConfig)

  private val aclChecker = AclSimpleCheck().accepted
  private val aopd       = new OwnerPermissionsScopeInitialization(
    acl => aclChecker.append(acl),
    Set(orgsPermissions.write, orgsPermissions.read)
  )

  private lazy val orgs                            = OrganizationsImpl(Set(aopd), config, xas)
  private lazy val orgDeleter: OrganizationDeleter = id => IO.raiseWhen(id == org1.label)(OrganizationNonEmpty(id))

  private val caller = Caller(alice, Set(alice, Anonymous, Authenticated(realm), Group("group", realm)))

  private val identities = IdentitiesDummy(caller)

  private lazy val routes = Route.seal(
    OrganizationsRoutes(
      identities,
      orgs,
      orgDeleter,
      aclChecker,
      DeltaSchemeDirectives.onlyResolveOrgUuid(ioFromMap(fixedUuid -> org1.label))
    )
  )

  private val org1CreatedMeta = orgMetadata(org1.label, fixedUuid)

  private val org1Created = jsonContentOf(
    "/organizations/org-resource.json",
    "label"       -> org1.label.value,
    "uuid"        -> fixedUuid.toString,
    "description" -> org1.description.value
  ) deepMerge org1CreatedMeta.removeKeys("@context")

  private val org1UpdatedMeta = orgMetadata(org1.label, fixedUuid, rev = 2)
  private val org1Updated     =
    org1Created deepMerge json"""{"description": "updated"}""" deepMerge org1UpdatedMeta.removeKeys("@context")

  private val org2CreatedMeta = orgMetadata(org2.label, fixedUuid, createdBy = alice, updatedBy = alice)

  private val org2Created = jsonContentOf(
    "/organizations/org-resource.json",
    "label" -> org2.label.value,
    "uuid"  -> fixedUuid.toString
  ).removeKeys("description") deepMerge org2CreatedMeta.removeKeys("@context")

  private val org2DeprecatedMeta =
    orgMetadata(org2.label, fixedUuid, rev = 2, deprecated = true, createdBy = alice, updatedBy = alice)

  "An OrganizationsRoute" should {

    "fail to create an organization without organizations/create permission" in {
      aclChecker.append(AclAddress.Root, Anonymous -> Set(events.read)).accepted
      val input = json"""{"description": "${org1.description.value}"}"""

      Put("/v1/orgs/org1", input.toEntity) ~> routes ~> check {
        response.status shouldEqual StatusCodes.Forbidden
        response.asJson shouldEqual jsonContentOf("errors/authorization-failed.json")
      }
    }

    "create a new organization" in {
      aclChecker
        .append(AclAddress.Root, Anonymous -> Set(orgsPermissions.create), caller.subject -> Set(orgsPermissions.write))
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
      json"""{"@context": ["${contexts.metadata}", "${contexts.organizations}", "${contexts.search}"], "_total": ${results.size}}""" deepMerge
        Json.obj("_results" -> Json.arr(results: _*))

    "list organizations" in {
      aclChecker.append(Label.unsafe("org2"), Anonymous -> Set(orgsPermissions.read)).accepted

      val expected = expectedResults(org1Updated.removeKeys("@context"), org2Created.removeKeys("@context"))
      Get("/v1/orgs") ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson should equalIgnoreArrayOrder(expected)
      }
      Get("/v1/orgs?label=or") ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson should equalIgnoreArrayOrder(expected)
      }
    }

    "list organizations with revision 2" in {
      Get("/v1/orgs?rev=2") ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson should equalIgnoreArrayOrder(expectedResults(org1Updated.removeKeys("@context")))
      }
    }

    "list organizations created by alice" in {
      Get(s"/v1/orgs?createdBy=${UrlUtils.encode(alice.asIri.toString)}") ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson should equalIgnoreArrayOrder(expectedResults(org2Created.removeKeys("@context")))
      }
    }

    "list only organizations for which the user has access" in {
      aclChecker.delete(Label.unsafe("org2")).accepted
      Get("/v1/orgs") ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson should equalIgnoreArrayOrder(
          expectedResults(
            org1Updated.removeKeys("@context")
          )
        )
      }
    }

    "deprecate an organization" in {
      Delete("/v1/orgs/org2?rev=1") ~> addCredentials(OAuth2BearerToken("alice")) ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson should equalIgnoreArrayOrder(org2DeprecatedMeta)
      }
    }

    "fail to deprecate an organization if the revision is omitted" in {
      Delete("/v1/orgs/org2") ~> addCredentials(OAuth2BearerToken("alice")) ~> routes ~> check {
        status shouldEqual StatusCodes.BadRequest
      }
    }

    "delete an organization" in {
      aclChecker.append(AclAddress.fromOrg(org2.label), caller.subject -> Set(orgsPermissions.delete)).accepted
      Delete("/v1/orgs/org2?prune=true") ~> addCredentials(OAuth2BearerToken("alice")) ~> routes ~> check {
        status shouldEqual StatusCodes.OK
      }
    }

    "fail when trying to delete a non-empty organization" in {
      aclChecker.append(AclAddress.fromOrg(org1.label), caller.subject -> Set(orgsPermissions.delete)).accepted
      Delete("/v1/orgs/org1?prune=true") ~> addCredentials(OAuth2BearerToken("alice")) ~> routes ~> check {
        status shouldEqual StatusCodes.Conflict
      }
    }

    "fail to delete an organization without organizations/delete permission" in {
      aclChecker.subtract(AclAddress.fromOrg(org2.label), caller.subject -> Set(orgsPermissions.delete)).accepted
      Delete("/v1/orgs/org2?prune=true") ~> addCredentials(OAuth2BearerToken("alice")) ~> routes ~> check {
        status shouldEqual StatusCodes.Forbidden
      }
    }

    "fail fetch an organization without organizations/read permission" in {
      aclChecker.delete(Label.unsafe("org1")).accepted
      forAll(
        Seq(
          "/v1/orgs/org2",
          s"/v1/orgs/$fixedUuid",
          s"/v1/orgs/$fixedUuid?rev=1",
          s"/v1/orgs/${UUID.randomUUID()}",
          s"/v1/orgs/${UUID.randomUUID()}?rev=1"
        )
      ) { path =>
        Get(path) ~> routes ~> check {
          response.asJson shouldEqual jsonContentOf("errors/authorization-failed.json")
          response.status shouldEqual StatusCodes.Forbidden
        }
      }
    }
  }

  def orgMetadata(
      label: Label,
      uuid: UUID,
      rev: Int = 1,
      deprecated: Boolean = false,
      createdBy: Subject = Anonymous,
      updatedBy: Subject = Anonymous
  ): Json =
    jsonContentOf(
      "organizations/org-route-metadata-response.json",
      "rev"        -> rev,
      "deprecated" -> deprecated,
      "createdBy"  -> createdBy.asIri,
      "updatedBy"  -> updatedBy.asIri,
      "label"      -> label,
      "uuid"       -> uuid
    )
}
