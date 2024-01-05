package ch.epfl.bluebrain.nexus.delta.routes

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Route
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.utils.{UUIDF, UrlUtils}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.sdk.ScopeInitializer
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.generators.OrganizationGen
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.model.OrganizationRejection.OrganizationNonEmpty
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.{OrganizationDeleter, OrganizationsConfig, OrganizationsImpl}
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions.{orgs => orgsPermissions}
import ch.epfl.bluebrain.nexus.delta.sdk.projects.OwnerPermissionsScopeInitialization
import ch.epfl.bluebrain.nexus.delta.sdk.utils.BaseRouteSpec
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Anonymous, Subject, User}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.testkit.scalatest.ProjectMatchers.deprecated
import io.circe.Json
import org.scalactic.source.Position

import java.util.UUID

class OrganizationsRoutesSpec extends BaseRouteSpec {

  private val fixedUuid             = UUID.randomUUID()
  implicit private val uuidF: UUIDF = UUIDF.fixed(fixedUuid)

  private val org1 = OrganizationGen.organization("org1", fixedUuid, Some("My description"))
  private val org2 = OrganizationGen.organization("org2", fixedUuid)

  private val config = OrganizationsConfig(eventLogConfig, pagination)

  private val aopd = new OwnerPermissionsScopeInitialization(
    acl => aclChecker.append(acl),
    Set(orgsPermissions.write, orgsPermissions.read)
  )

  private val orgInit                              = ScopeInitializer.withoutErrorStore(Set(aopd))
  private lazy val orgs                            = OrganizationsImpl(orgInit, config, xas, clock)
  private lazy val orgDeleter: OrganizationDeleter = id => IO.raiseWhen(id == org1.label)(OrganizationNonEmpty(id))

  private val superUser                      = User("superUser", Label.unsafe(genString()))
  private val userWithCreatePermission       = User("userWithCreatePermission", Label.unsafe(genString()))
  private val userThatCreatesOrg2            = User("userThatCreatesOrg2", Label.unsafe(genString()))
  private val userWithWritePermission        = User("userWithWritePermission", Label.unsafe(genString()))
  private val userWithDeletePermission       = User("userWithDeletePermission", Label.unsafe(genString()))
  private val userWithReadPermission         = User("userWithReadPermission", Label.unsafe(genString()))
  private val userWithOrgSpecificPermissions = User("userWithOrgSpecificPermissions", Label.unsafe(genString()))
  private val (aclChecker, identities)       = usersFixture(
    (
      superUser,
      AclAddress.Root,
      Set(orgsPermissions.create, orgsPermissions.write, orgsPermissions.read, orgsPermissions.delete)
    ),
    (userWithCreatePermission, AclAddress.Root, Set(orgsPermissions.create)),
    (userThatCreatesOrg2, AclAddress.Root, Set(orgsPermissions.create)),
    (userWithWritePermission, AclAddress.Root, Set(orgsPermissions.write)),
    (userWithReadPermission, AclAddress.Root, Set(orgsPermissions.read)),
    (userWithDeletePermission, AclAddress.Root, Set(orgsPermissions.delete)),
    (userWithOrgSpecificPermissions, AclAddress.Root, Set.empty)
  )

  private lazy val routes = Route.seal(
    OrganizationsRoutes(
      identities,
      orgs,
      orgDeleter,
      aclChecker
    )
  )

  private val org1CreatedMeta =
    orgMetadata(org1.label, fixedUuid, createdBy = userWithCreatePermission, updatedBy = userWithCreatePermission)

  private val org1Created = jsonContentOf(
    "organizations/org-resource.json",
    "label"       -> org1.label.value,
    "uuid"        -> fixedUuid.toString,
    "description" -> org1.description.value
  ) deepMerge org1CreatedMeta.removeKeys("@context")

  private val org1UpdatedMeta = orgMetadata(
    org1.label,
    fixedUuid,
    rev = 2,
    createdBy = userWithCreatePermission,
    updatedBy = userWithWritePermission
  )
  private val org1Updated     =
    org1Created deepMerge json"""{"description": "updated"}""" deepMerge org1UpdatedMeta.removeKeys("@context")

  private val org2CreatedMeta =
    orgMetadata(org2.label, fixedUuid, createdBy = userThatCreatesOrg2, updatedBy = userThatCreatesOrg2)

  private val org2Created = jsonContentOf(
    "organizations/org-resource.json",
    "label" -> org2.label.value,
    "uuid"  -> fixedUuid.toString
  ).removeKeys("description") deepMerge org2CreatedMeta.removeKeys("@context")

  private val org2DeprecatedMeta =
    orgMetadata(
      org2.label,
      fixedUuid,
      rev = 2,
      deprecated = true,
      createdBy = userThatCreatesOrg2,
      updatedBy = userWithWritePermission
    )

  "An OrganizationsRoute" should {

    "fail to create an organization without organizations/create permission" in {
      val input = json"""{"description": "${org1.description.value}"}"""

      Put("/v1/orgs/org1", input.toEntity) ~> routes ~> check {
        response.shouldBeForbidden
      }
    }

    "create a new organization" in {
      val input = json"""{"description": "${org1.description.value}"}"""

      Put("/v1/orgs/org1", input.toEntity) ~> as(userWithCreatePermission) ~> routes ~> check {
        status shouldEqual StatusCodes.Created
        response.asJson shouldEqual org1CreatedMeta
      }
    }

    "update an existing organization" in {
      val input = json"""{"description": "updated"}"""

      Put("/v1/orgs/org1?rev=1", input.toEntity) ~> as(userWithWritePermission) ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson shouldEqual org1UpdatedMeta
      }
    }

    "fetch an organization by label" in {
      Get("/v1/orgs/org1") ~> as(userWithReadPermission) ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson shouldEqual org1Updated
      }
    }

    "fetch an organization by label and rev" in {
      Get("/v1/orgs/org1?rev=1") ~> as(userWithReadPermission) ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson shouldEqual org1Created
      }
    }

    "reject the creation of a organization if it already exists" in {
      val input = json"""{"description": "${org1.description.value}"}"""

      Put("/v1/orgs/org1", input.toEntity) ~> as(userWithCreatePermission) ~> routes ~> check {
        status shouldEqual StatusCodes.Conflict
        response.asJson shouldEqual jsonContentOf("organizations/already-exists.json", "org" -> org1.label.value)
      }
    }

    "fail fetching an organization by label and rev when rev is invalid" in {
      Get("/v1/orgs/org1?rev=4") ~> as(userWithReadPermission) ~> routes ~> check {
        status shouldEqual StatusCodes.NotFound
        response.asJson shouldEqual jsonContentOf("errors/revision-not-found.json", "provided" -> 4, "current" -> 2)
      }
    }

    def expectedResults(results: Json*): Json =
      json"""{"@context": ["${contexts.metadata}", "${contexts.organizations}", "${contexts.search}"], "_total": ${results.size}}""" deepMerge
        Json.obj("_results" -> Json.arr(results: _*))

    "list organizations" in {

      Put("/v1/orgs/org2", Json.obj().toEntity) ~> as(userThatCreatesOrg2) ~> routes ~> check {
        status shouldEqual StatusCodes.Created
        response.asJson shouldEqual org2CreatedMeta
      }

      val expected = expectedResults(org1Updated.removeKeys("@context"), org2Created.removeKeys("@context"))
      Get("/v1/orgs") ~> as(userWithReadPermission) ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson should equalIgnoreArrayOrder(expected)
      }
      Get("/v1/orgs?label=or") ~> as(userWithReadPermission) ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson should equalIgnoreArrayOrder(expected)
      }
    }

    "list organizations with revision 2" in {
      Get("/v1/orgs?rev=2") ~> as(userWithReadPermission) ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson should equalIgnoreArrayOrder(expectedResults(org1Updated.removeKeys("@context")))
      }
    }

    "list organizations created by a user" in {
      Get(s"/v1/orgs?createdBy=${UrlUtils.encode(userThatCreatesOrg2.asIri.toString)}") ~> as(
        superUser
      ) ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson should equalIgnoreArrayOrder(expectedResults(org2Created.removeKeys("@context")))
      }
    }

    "list only organizations for which the user has access" in {
      aclChecker
        .append(AclAddress.Organization(org1.label), userWithOrgSpecificPermissions -> Set(orgsPermissions.read))
        .accepted
      Get("/v1/orgs") ~> as(userWithOrgSpecificPermissions) ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson should equalIgnoreArrayOrder(
          expectedResults(
            org1Updated.removeKeys("@context")
          )
        )
      }
    }

    "deprecate an organization" in {
      Delete("/v1/orgs/org2?rev=1") ~> as(userWithWritePermission) ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson should equalIgnoreArrayOrder(org2DeprecatedMeta)
      }
    }

    "fail to deprecate an organization if the revision is omitted" in {
      Delete("/v1/orgs/org2") ~> as(userWithWritePermission) ~> routes ~> check {
        status shouldEqual StatusCodes.BadRequest
      }
    }

    "fail to deprecate an organization if 'prune' is specified for deletion" in {
      Delete("/v1/orgs/org2?rev=1&prune=true") ~> as(userWithWritePermission) ~> routes ~> check {
        status shouldEqual StatusCodes.BadRequest
      }
    }

    "delete an organization" in {
      Delete("/v1/orgs/org2?prune=true") ~> as(userWithDeletePermission) ~> routes ~> check {
        status shouldEqual StatusCodes.OK
      }
    }

    "fail to delete an organization if 'prune' is false but no revision is specified for deprecation" in {
      Delete("/v1/orgs/org2?prune=false") ~> as(userWithDeletePermission) ~> routes ~> check {
        status shouldEqual StatusCodes.BadRequest
      }
    }

    "fail when trying to delete a non-empty organization" in {
      Delete("/v1/orgs/org1?prune=true") ~> as(userWithDeletePermission) ~> routes ~> check {
        status shouldEqual StatusCodes.Conflict
      }
    }

    "fail to delete an organization without organizations/delete permission" in {
      Delete("/v1/orgs/org2?prune=true") ~> routes ~> check {
        status shouldEqual StatusCodes.Forbidden
      }
    }

    "fail fetch an organization without organizations/read permission" in {
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
          response.shouldBeForbidden
        }
      }
    }

    "undeprecate an organisation" in {
      val org = thereIsADeprecatedOrganization

      Put(s"/v1/orgs/$org/undeprecate?rev=2") ~> as(userWithWritePermission) ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson should not(be(deprecated))
      }

      latestRevisionOfOrganization(org) should not(be(deprecated))
    }

    "fail to undeprecate an organisation without organizations/write permission" in {
      val org = thereIsADeprecatedOrganization

      Put(s"/v1/orgs/$org/undeprecate?rev=2") ~> routes ~> check {
        response.shouldBeForbidden
      }

      latestRevisionOfOrganization(org) should be(deprecated)
    }

    "fail to undeprecate an organisation if the revision is omitted" in {
      val org = thereIsADeprecatedOrganization

      Put(s"/v1/orgs/$org/undeprecate") ~> as(userWithWritePermission) ~> routes ~> check {
        status shouldEqual StatusCodes.BadRequest
      }

      latestRevisionOfOrganization(org) should be(deprecated)
    }

    "fail to undeprecate an organisation if the revision is incorrect" in {
      val org = thereIsADeprecatedOrganization

      Put(s"/v1/orgs/$org/undeprecate?rev=1") ~> as(userWithWritePermission) ~> routes ~> check {
        status shouldEqual StatusCodes.Conflict
      }

      latestRevisionOfOrganization(org) should be(deprecated)
    }

    "fail to undeprecate an organisation if it is not deprecated" in {
      val org = thereIsAnOrganization

      Put(s"/v1/orgs/$org/undeprecate?rev=1") ~> as(userWithWritePermission) ~> routes ~> check {
        status shouldEqual StatusCodes.BadRequest
      }

      latestRevisionOfOrganization(org) should not(be(deprecated))
    }

    "fail to undeprecate an organisation if it does not exist" in {
      Put(s"/v1/orgs/does-not-exist/undeprecate?rev=1") ~> as(userWithWritePermission) ~> routes ~> check {
        status shouldEqual StatusCodes.NotFound
      }
    }
  }

  private def thereIsAnOrganization = {
    val org     = genString()
    val payload = json"""{"description": "${genString()}"}"""
    Put(s"/v1/orgs/$org", payload.toEntity) ~> as(superUser) ~> routes ~> check {
      status shouldEqual StatusCodes.Created
    }
    org
  }

  private def thereIsADeprecatedOrganization(implicit pos: Position) = {
    val org = thereIsAnOrganization
    deprecateOrganization(org, 1)
    org
  }

  private def deprecateOrganization(org: String, rev: Int)(implicit pos: Position): Unit = {
    Delete(s"/v1/orgs/$org?rev=$rev") ~> as(superUser) ~> routes ~> check {
      status shouldEqual StatusCodes.OK
      response.asJson should be(deprecated)
    }
    ()
  }

  private def latestRevisionOfOrganization(org: String)(implicit pos: Position): Json = {
    Get(s"/v1/orgs/$org") ~> as(superUser) ~> routes ~> check {
      status shouldEqual StatusCodes.OK
      response.asJson
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
