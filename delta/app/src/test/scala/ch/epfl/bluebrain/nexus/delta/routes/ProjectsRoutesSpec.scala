package ch.epfl.bluebrain.nexus.delta.routes

import akka.http.scaladsl.model.MediaTypes.`text/html`
import akka.http.scaladsl.model.headers.{Accept, Location}
import akka.http.scaladsl.model.{StatusCodes, Uri}
import akka.http.scaladsl.server.Route
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.utils.{UUIDF, UrlUtils}
import ch.epfl.bluebrain.nexus.delta.sdk.ScopeInitializer
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ProjectGen.defaultApiMappings
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.model.Organization
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.model.OrganizationRejection.{OrganizationIsDeprecated, OrganizationNotFound}
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions.{projects => projectsPermissions, resources}
import ch.epfl.bluebrain.nexus.delta.sdk.projects.Projects.FetchOrganization
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ProjectRejection.WrappedOrganizationRejection
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model._
import ch.epfl.bluebrain.nexus.delta.sdk.projects.{ProjectsConfig, ProjectsImpl, ProjectsStatistics}
import ch.epfl.bluebrain.nexus.delta.sdk.provisioning.{AutomaticProvisioningConfig, ProjectProvisioning}
import ch.epfl.bluebrain.nexus.delta.sdk.utils.BaseRouteSpec
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Anonymous, Subject, User}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef}
import ch.epfl.bluebrain.nexus.testkit.scalatest.ProjectMatchers.deprecated
import io.circe.Json
import org.scalactic.source.Position
import org.scalatest.BeforeAndAfterAll

import java.time.Instant
import java.util.UUID
import scala.concurrent.duration._

class ProjectsRoutesSpec extends BaseRouteSpec with BeforeAndAfterAll {

  implicit override def patienceConfig: PatienceConfig = PatienceConfig(6.seconds, 10.milliseconds)

  private val projectUuid           = UUID.randomUUID()
  implicit private val uuidF: UUIDF = UUIDF.fixed(projectUuid)

  private val orgUuid = UUID.randomUUID()

  private val provisionedRealm = Label.unsafe("realm2")

  private val userWithWritePermission             = User("userWithWritePermission", Label.unsafe(genString()))
  private val userWithCreatePermission            = User("userWithCreatePermission", Label.unsafe(genString()))
  private val userWithReadPermission              = User("userWithReadPermission", Label.unsafe(genString()))
  private val userWithDeletePermission            = User("userWithDeletePermission", Label.unsafe(genString()))
  private val userWithResourcesReadPermission     = User("userWithResourcesReadPermission", Label.unsafe(genString()))
  private val userWithReadSingleProjectPermission =
    User("userWithReadSingleProjectPermission", Label.unsafe(genString()))

  private val superUser       = User("superUser", Label.unsafe(genString()))
  private val provisionedUser = User("user1", provisionedRealm)
  private val invalidUser     = User("!@#%^", provisionedRealm)

  private val org1     = Label.unsafe("org1")
  private val org2     = Label.unsafe("org2")
  private val usersOrg = Label.unsafe("users-org")

  private val ref = ProjectRef.unsafe("org1", "proj")

  private def fetchOrg: FetchOrganization = {
    case `org1`     => IO.pure(Organization(org1, orgUuid, None))
    case `usersOrg` => IO.pure(Organization(usersOrg, orgUuid, None))
    case `org2`     => IO.raiseError(WrappedOrganizationRejection(OrganizationIsDeprecated(org2)))
    case other      => IO.raiseError(WrappedOrganizationRejection(OrganizationNotFound(other)))
  }

  private val provisioningConfig = AutomaticProvisioningConfig(
    enabled = true,
    permissions = Set(resources.read, resources.write, projectsPermissions.read),
    enabledRealms = Map(Label.unsafe("realm2") -> Label.unsafe("users-org")),
    ProjectFields(
      Some("Auto provisioned project"),
      ApiMappings.empty,
      Some(PrefixIri.unsafe(iri"http://example.com/base/")),
      Some(PrefixIri.unsafe(iri"http://example.com/vocab/"))
    )
  )

  implicit private val projectsConfig: ProjectsConfig = ProjectsConfig(eventLogConfig, pagination, deletionConfig)

  private val projectStats = ProjectStatistics(10, 10, Instant.EPOCH)

  private val projectsStatistics: ProjectsStatistics = {
    case `ref` => IO.pure(Some(projectStats))
    case _     => IO.none
  }

  val (aclCheck, identities) = usersFixture(
    (userWithWritePermission, AclAddress.Root, Set(projectsPermissions.write)),
    (userWithReadPermission, AclAddress.Root, Set(projectsPermissions.read)),
    (userWithDeletePermission, AclAddress.Root, Set(projectsPermissions.delete)),
    (userWithResourcesReadPermission, AclAddress.Root, Set(resources.read)),
    (userWithCreatePermission, AclAddress.Root, Set(projectsPermissions.create)),
    (
      superUser,
      AclAddress.Root,
      Set(projectsPermissions.create, projectsPermissions.read, projectsPermissions.write, projectsPermissions.delete)
    ),
    (provisionedUser, AclAddress.Root, Set(projectsPermissions.read)),
    (invalidUser, AclAddress.Root, Set.empty),
    (alice, AclAddress.Root, Set(projectsPermissions.create)),
    (userWithReadSingleProjectPermission, AclAddress.Project(ref), Set(projectsPermissions.read))
  )

  private val noopInit          = ScopeInitializer.noErrorStore(Set.empty)
  private lazy val projects     =
    ProjectsImpl(fetchOrg, _ => IO.unit, noopInit, defaultApiMappings, projectsConfig, xas, clock)
  private lazy val provisioning =
    ProjectProvisioning(aclCheck.append, projects, provisioningConfig)
  private lazy val routes       = Route.seal(
    ProjectsRoutes(
      identities,
      aclCheck,
      projects,
      projectsStatistics,
      provisioning
    )
  )

  val desc  = "Project description"
  val base  = "https://localhost/base/"
  val vocab = "https://localhost/voc/"

  val payload = jsonContentOf("projects/create.json", "description" -> desc, "base" -> base, "vocab" -> vocab)

  val payloadUpdated =
    jsonContentOf("projects/create.json", "description" -> "New description", "base" -> base, "vocab" -> vocab)

  val anotherPayload = jsonContentOf("projects/create.json", "description" -> desc)

  "A project route" should {

    "fail to create a project without projects/create permission" in {
      Put("/v1/projects/org1/proj", payload.toEntity) ~> routes ~> check {
        response.shouldBeForbidden
      }
    }

    "create a project" in {
      Put("/v1/projects/org1/proj", payload.toEntity) ~> as(userWithCreatePermission) ~> routes ~> check {
        status shouldEqual StatusCodes.Created
        val ref = ProjectRef(Label.unsafe("org1"), Label.unsafe("proj"))
        response.asJson should equalIgnoreArrayOrder(
          projectMetadata(
            ref,
            "proj",
            projectUuid,
            "org1",
            orgUuid,
            rev = 1,
            createdBy = userWithCreatePermission,
            updatedBy = userWithCreatePermission
          )
        )
      }
    }

    "reject the creation of a project without a label" in {
      Put("/v1/projects/org1", anotherPayload.toEntity) ~> as(userWithCreatePermission) ~> routes ~> check {
        status shouldEqual StatusCodes.MethodNotAllowed
      }
    }

    "reject the creation of a project which already exists" in {
      Put("/v1/projects/org1/proj", payload.toEntity) ~> as(userWithCreatePermission) ~> routes ~> check {
        status shouldEqual StatusCodes.Conflict
        response.asJson shouldEqual jsonContentOf(
          "projects/errors/already-exists.json",
          "org"  -> "org1",
          "proj" -> "proj"
        )
      }
    }

    "reject the creation of a project on a deprecated organization" in {
      Put("/v1/projects/org2/proj3", payload.toEntity) ~> as(userWithCreatePermission) ~> routes ~> check {
        status shouldEqual StatusCodes.BadRequest
        response.asJson shouldEqual jsonContentOf("projects/errors/org-deprecated.json")
      }
    }

    "fail to update a project without projects/write permission" in {
      Put("/v1/projects/org1/proj?rev=1", payloadUpdated.toEntity) ~> routes ~> check {
        response.shouldBeForbidden
      }
    }

    "update a project" in {
      Put("/v1/projects/org1/proj?rev=1", payloadUpdated.toEntity) ~> as(userWithWritePermission) ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        val ref = ProjectRef.unsafe("org1", "proj")
        response.asJson should equalIgnoreArrayOrder(
          projectMetadata(
            ref,
            "proj",
            projectUuid,
            "org1",
            orgUuid,
            rev = 2,
            updatedBy = userWithWritePermission,
            createdBy = userWithCreatePermission
          )
        )
      }
    }

    "reject the update of a project without name" in {
      Put("/v1/projects/org1?rev=2", payload.toEntity) ~> as(userWithWritePermission) ~> routes ~> check {
        status shouldEqual StatusCodes.MethodNotAllowed
      }
    }

    "reject the update of a non-existent project" in {
      Put("/projects/org1/unknown?rev=1", payload.toEntity) ~> as(userWithWritePermission) ~> routes ~> check {
        status shouldEqual StatusCodes.NotFound
      }
    }

    "reject the update of a project at a non-existent revision" in {
      Put("/v1/projects/org1/proj?rev=42", payloadUpdated.toEntity) ~> as(userWithWritePermission) ~> routes ~> check {
        status shouldEqual StatusCodes.Conflict
        response.asJson should equalIgnoreArrayOrder(
          jsonContentOf("projects/errors/incorrect-rev.json", "provided" -> 42, "expected" -> 2)
        )
      }
    }

    "fail to deprecate a project without projects/write permission" in {
      Delete("/v1/projects/org1/proj?rev=2") ~> routes ~> check {
        response.shouldBeForbidden
      }
      latestRevisionOfProject("org1", "proj") should not(be(deprecated))
    }

    "reject the deprecation of a project without rev" in {
      Delete("/v1/projects/org1/proj") ~> as(userWithWritePermission) ~> routes ~> check {
        status shouldEqual StatusCodes.BadRequest
        response.asJson shouldEqual jsonContentOf("errors/missing-query-param.json", "field" -> "rev")
      }
      latestRevisionOfProject("org1", "proj") should not(be(deprecated))
    }

    "deprecate a project" in {
      Delete("/v1/projects/org1/proj?rev=2") ~> as(userWithWritePermission) ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        val ref = ProjectRef(Label.unsafe("org1"), Label.unsafe("proj"))
        response.asJson should equalIgnoreArrayOrder(
          projectMetadata(
            ref,
            "proj",
            projectUuid,
            "org1",
            orgUuid,
            rev = 3,
            deprecated = true,
            createdBy = userWithCreatePermission,
            updatedBy = userWithWritePermission
          )
        )
      }
    }

    "reject the deprecation of a already deprecated project" in {
      Delete("/v1/projects/org1/proj?rev=3") ~> as(userWithWritePermission) ~> routes ~> check {
        status shouldEqual StatusCodes.BadRequest
        response.asJson shouldEqual jsonContentOf(
          "projects/errors/project-deprecated.json",
          "org"  -> "org1",
          "proj" -> "proj"
        )
      }
    }

    val fetchProjRev2 = projectsFetchJson(
      "org1",
      "proj",
      orgUuid,
      projectUuid,
      2L,
      deprecated = false,
      markedForDeletion = false,
      "New description",
      base,
      vocab,
      createdBy = Some(userWithCreatePermission),
      updatedBy = Some(userWithWritePermission)
    )

    val fetchProjRev3 = projectsFetchJson(
      "org1",
      "proj",
      orgUuid,
      projectUuid,
      3L,
      deprecated = true,
      markedForDeletion = false,
      "New description",
      base,
      vocab,
      createdBy = Some(userWithCreatePermission),
      updatedBy = Some(userWithWritePermission)
    )

    val fetchProj2 = projectsFetchJson(
      "org1",
      "proj2",
      orgUuid,
      projectUuid,
      1L,
      deprecated = false,
      markedForDeletion = false,
      "Project description",
      "http://localhost/v1/resources/org1/proj2/_/",
      "http://localhost/v1/vocabs/org1/proj2/",
      createdBy = Some(alice),
      updatedBy = Some(alice)
    )

    "fail to fetch a project without projects/read permission" in {
      forAll(
        Seq(
          "/v1/projects/org1/proj",
          s"/v1/projects/$orgUuid/$projectUuid",
          s"/v1/projects/$orgUuid/$projectUuid?rev=2",
          s"/v1/projects/$orgUuid/${UUID.randomUUID()}",
          s"/v1/projects/$orgUuid/${UUID.randomUUID()}?rev=2"
        )
      ) { path =>
        Get(path) ~> routes ~> check {
          response.shouldBeForbidden
        }
      }
    }

    "fetch a project" in {
      Get("/v1/projects/org1/proj") ~> as(userWithReadPermission) ~> routes ~> check {
        status shouldEqual StatusCodes.OK

        response.asJson should equalIgnoreArrayOrder(fetchProjRev3)
      }
    }

    "fetch a specific project revision" in {
      Get("/v1/projects/org1/proj?rev=2") ~> as(userWithReadPermission) ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson should equalIgnoreArrayOrder(fetchProjRev2)
      }
    }

    "fetch a project with an incorrect revision" in {
      Get("/v1/projects/org1/proj?rev=42") ~> as(userWithReadPermission) ~> routes ~> check {
        status shouldEqual StatusCodes.NotFound
        response.asJson shouldEqual jsonContentOf(
          "errors/revision-not-found.json",
          "provided" -> 42,
          "current"  -> 3
        )
      }
    }

    "fetch an unknown project" in {
      Get(s"/v1/projects/org1/unknown") ~> as(userWithReadPermission) ~> routes ~> check {
        status shouldEqual StatusCodes.NotFound
        response.asJson shouldEqual jsonContentOf("projects/errors/project-not-found.json", "proj" -> "org1/unknown")
      }
    }

    "list all projects" in {
      Put("/v1/projects/org1/proj2", anotherPayload.toEntity) ~> as(alice) ~> routes ~> check {
        status shouldEqual StatusCodes.Created
      }

      val expected = expectedResults(fetchProjRev3.removeKeys("@context"), fetchProj2.removeKeys("@context"))
      Get("/v1/projects") ~> as(userWithReadPermission) ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson should equalIgnoreArrayOrder(expected)
      }
      Get("/v1/projects?label=p") ~> as(userWithReadPermission) ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson should equalIgnoreArrayOrder(expected)
      }
    }

    "list all projects for organization" in {
      val expected = expectedResults(fetchProjRev3.removeKeys("@context"), fetchProj2.removeKeys("@context"))

      Get("/v1/projects/org1") ~> as(userWithReadPermission) ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson should equalIgnoreArrayOrder(expected)
      }
      Get("/v1/projects/org1?label=p") ~> as(userWithReadPermission) ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson should equalIgnoreArrayOrder(expected)
      }
    }

    "list all deprecated projects " in {
      Get("/v1/projects?deprecated=true") ~> as(userWithReadPermission) ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson should equalIgnoreArrayOrder(
          expectedResults(
            fetchProjRev3.removeKeys("@context")
          )
        )
      }
    }

    "list all projects updated by Alice" in {
      Get(s"/v1/projects?updatedBy=${UrlUtils.encode(alice.asIri.toString)}&label=p") ~> as(
        userWithReadPermission
      ) ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson should equalIgnoreArrayOrder(
          expectedResults(
            fetchProj2.removeKeys("@context")
          )
        )
      }
    }

    "list all projects user has access to" in {
      Get("/v1/projects") ~> as(userWithReadSingleProjectPermission) ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson should equalIgnoreArrayOrder(
          expectedResults(
            fetchProjRev3.removeKeys("@context")
          )
        )
      }
    }

    "fail to get the project statistics without resources/read permission" in {
      Get("/v1/projects/org1/proj/statistics") ~> routes ~> check {
        response.shouldBeForbidden
      }
    }

    "fail to get the project statistics for an unknown project" in {
      Get("/v1/projects/org1/unknown/statistics") ~> as(userWithResourcesReadPermission) ~> routes ~> check {
        status shouldEqual StatusCodes.NotFound
        response.asJson shouldEqual jsonContentOf("projects/errors/project-not-found.json", "proj" -> "org1/unknown")
      }
    }

    "get the project statistics" in {
      Get("/v1/projects/org1/proj/statistics") ~> as(userWithResourcesReadPermission) ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson shouldEqual json"""{
          "@context" : "https://bluebrain.github.io/nexus/contexts/statistics.json",
          "lastProcessedEventDateTime" : "1970-01-01T00:00:00Z",
          "eventsCount" : 10,
          "resourcesCount" : 10
        }"""
      }
    }

    "provision project for user when listing" in {
      Get("/v1/projects/users-org") ~> as(provisionedUser) ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson.asObject.value("_total").value.asNumber.value.toInt.value shouldEqual 1
      }

      Get("/v1/projects/users-org/user1") ~> as(provisionedUser) ~> routes ~> check {
        status shouldEqual StatusCodes.OK
      }
    }

    "return error when failed to provision project" in {
      Get("/v1/projects/users-org") ~> as(invalidUser) ~> routes ~> check {
        status shouldEqual StatusCodes.InternalServerError
      }
    }

    "redirect to fusion for the latest version if the Accept header is set to text/html" in {
      Get("/v1/projects/users-org/user1") ~> Accept(`text/html`) ~> routes ~> check {
        response.status shouldEqual StatusCodes.SeeOther
        response.header[Location].value.uri shouldEqual Uri("https://bbp.epfl.ch/nexus/web/admin/users-org/user1")
      }
    }

    "fail to delete a project without projects/delete permission" in {
      Delete("/v1/projects/org1/proj?rev=3&prune=true") ~> routes ~> check {
        response.shouldBeForbidden
      }
    }

    "delete a project" in {
      Delete("/v1/projects/org1/proj?rev=3&prune=true") ~> as(userWithDeletePermission) ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        val ref = ProjectRef(Label.unsafe("org1"), Label.unsafe("proj"))
        response.asJson should equalIgnoreArrayOrder(
          projectMetadata(
            ref,
            "proj",
            projectUuid,
            "org1",
            orgUuid,
            rev = 4,
            deprecated = true,
            markedForDeletion = true,
            createdBy = userWithCreatePermission,
            updatedBy = userWithDeletePermission
          )
        )
      }
    }

    "fail to undeprecate a project without projects/write permission" in {
      val (org, project) = thereIsADeprecatedProject
      Put(s"/v1/projects/$org/$project/undeprecate?rev=2") ~> as(userWithReadPermission) ~> routes ~> check {
        response.shouldBeForbidden
      }
      latestRevisionOfProject(org, project) should be(deprecated)
    }

    "fail to undeprecate a project if it is not deprecated" in {
      val (org, project) = thereIsAProject
      Put(s"/v1/projects/$org/$project/undeprecate?rev=1") ~> as(superUser) ~> routes ~> check {
        status shouldEqual StatusCodes.BadRequest
        response.asJson shouldEqual jsonContentOf(
          "projects/errors/project-not-deprecated.json",
          "org"  -> org,
          "proj" -> project
        )
      }
    }

    "undeprecate a project" in {
      val (org, project) = thereIsADeprecatedProject

      Put(s"/v1/projects/$org/$project/undeprecate?rev=2") ~> as(userWithWritePermission) ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson should not(be(deprecated))
      }

      latestRevisionOfProject(org, project) should not(be(deprecated))
    }
  }

  private def thereIsAProject = {
    val org     = org1.value
    val project = genString()
    Put(s"/v1/projects/$org/$project", payload.toEntity) ~> as(superUser) ~> routes ~> check {
      status shouldEqual StatusCodes.Created
    }
    org -> project
  }

  private def thereIsADeprecatedProject(implicit pos: Position) = {
    val (org, project) = thereIsAProject
    deprecateProject(org, project, 1)
    (org, project)
  }

  private def deprecateProject(org: String, project: String, rev: Int)(implicit pos: Position): Unit = {
    Delete(s"/v1/projects/$org/$project?rev=$rev") ~> as(superUser) ~> routes ~> check {
      status shouldEqual StatusCodes.OK
      response.asJson should be(deprecated)
    }
    ()
  }

  private def latestRevisionOfProject(org: String, project: String): Json = {
    Get(s"/v1/projects/$org/$project") ~> as(superUser) ~> routes ~> check {
      status shouldEqual StatusCodes.OK
      response.asJson
    }
  }

  def projectMetadata(
      ref: ProjectRef,
      label: String,
      uuid: UUID,
      organizationLabel: String,
      organizationUuid: UUID,
      rev: Int = 1,
      deprecated: Boolean = false,
      markedForDeletion: Boolean = false,
      createdBy: Subject = Anonymous,
      updatedBy: Subject = Anonymous
  ): Json =
    jsonContentOf(
      "projects/project-route-metadata-response.json",
      "project"           -> ref,
      "rev"               -> rev,
      "deprecated"        -> deprecated,
      "markedForDeletion" -> markedForDeletion,
      "createdBy"         -> createdBy.asIri,
      "updatedBy"         -> updatedBy.asIri,
      "label"             -> label,
      "uuid"              -> uuid,
      "organization"      -> organizationLabel,
      "organizationUuid"  -> organizationUuid
    )

  def projectsFetchJson(
      org: String,
      project: String,
      orgUuid: UUID,
      projectUuid: UUID,
      revision: Long,
      deprecated: Boolean,
      markedForDeletion: Boolean,
      description: String,
      base: String,
      vocab: String,
      createdBy: Option[User],
      updatedBy: Option[User]
  ): Json = {
    jsonContentOf(
      "projects/fetch.json",
      "org"               -> org,
      "proj"              -> project,
      "orgUuid"           -> orgUuid,
      "uuid"              -> projectUuid,
      "rev"               -> revision,
      "deprecated"        -> deprecated,
      "markedForDeletion" -> markedForDeletion,
      "description"       -> description,
      "base"              -> base,
      "vocab"             -> vocab,
      "createdBy"         -> createdBy.orNull,
      "updatedBy"         -> updatedBy.orNull
    )
  }

  def expectedResults(results: Json*): Json =
    Json.obj(
      "@context" -> Json.arr(
        Json.fromString("https://bluebrain.github.io/nexus/contexts/metadata.json"),
        Json.fromString("https://bluebrain.github.io/nexus/contexts/projects.json"),
        Json.fromString("https://bluebrain.github.io/nexus/contexts/search.json")
      ),
      "_total"   -> Json.fromInt(results.size),
      "_results" -> Json.arr(results: _*)
    )
}
