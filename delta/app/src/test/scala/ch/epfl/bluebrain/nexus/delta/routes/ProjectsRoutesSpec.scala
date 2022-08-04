package ch.epfl.bluebrain.nexus.delta.routes

import akka.http.scaladsl.model.MediaTypes.`text/html`
import akka.http.scaladsl.model.headers.{Accept, Location, OAuth2BearerToken}
import akka.http.scaladsl.model.{StatusCodes, Uri}
import akka.http.scaladsl.server.Route
import ch.epfl.bluebrain.nexus.delta.kernel.utils.{UUIDF, UrlUtils}
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclSimpleCheck
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaSchemeDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ProjectGen.defaultApiMappings
import ch.epfl.bluebrain.nexus.delta.sdk.identities.IdentitiesDummy
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.model.Organization
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.model.OrganizationRejection.{OrganizationIsDeprecated, OrganizationNotFound}
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions.{events, resources, projects => projectsPermissions}
import ch.epfl.bluebrain.nexus.delta.sdk.projects.Projects.FetchOrganization
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ProjectRejection.WrappedOrganizationRejection
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model._
import ch.epfl.bluebrain.nexus.delta.sdk.projects.{ProjectsConfig, ProjectsImpl, ProjectsStatistics}
import ch.epfl.bluebrain.nexus.delta.sdk.provisioning.{AutomaticProvisioningConfig, ProjectProvisioning}
import ch.epfl.bluebrain.nexus.delta.sdk.utils.BaseRouteSpec
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Anonymous, Authenticated, Group, Subject, User}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef}
import io.circe.Json
import monix.bio.{IO, UIO}

import java.time.Instant
import java.util.UUID
import scala.concurrent.duration._

class ProjectsRoutesSpec extends BaseRouteSpec {

  implicit override def patienceConfig: PatienceConfig = PatienceConfig(6.seconds, 10.milliseconds)

  private val projectUuid           = UUID.randomUUID()
  implicit private val uuidF: UUIDF = UUIDF.fixed(projectUuid)

  private val orgUuid = UUID.randomUUID()

  private val provisionedRealm  = Label.unsafe("realm2")
  private val caller            = Caller(alice, Set(alice, Anonymous, Authenticated(realm), Group("group", realm)))
  private val provisionedUser   = User("user1", provisionedRealm)
  private val provisionedCaller =
    Caller(
      provisionedUser,
      Set(provisionedUser, Anonymous, Authenticated(provisionedRealm), Group("group", provisionedRealm))
    )
  private val invalidUser       = User("!@#%^", provisionedRealm)
  private val invalidCaller     =
    Caller(invalidUser, Set(invalidUser, Anonymous, Authenticated(provisionedRealm), Group("group", provisionedRealm)))

  private val identities = IdentitiesDummy(caller, provisionedCaller, invalidCaller)

  private val asAlice       = addCredentials(OAuth2BearerToken("alice"))
  private val asProvisioned = addCredentials(OAuth2BearerToken("user1"))
  private val asInvalid     = addCredentials(OAuth2BearerToken("!@#%^"))

  private val aclCheck: AclSimpleCheck = AclSimpleCheck().accepted

  private val org1     = Label.unsafe("org1")
  private val org2     = Label.unsafe("org2")
  private val usersOrg = Label.unsafe("users-org")

  private val ref = ProjectRef.unsafe("org1", "proj")

  private def fetchOrg: FetchOrganization = {
    case `org1`     => UIO.pure(Organization(org1, orgUuid, None))
    case `usersOrg` => UIO.pure(Organization(usersOrg, orgUuid, None))
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

  implicit private val projectsConfig: ProjectsConfig =
    ProjectsConfig(eventLogConfig, pagination, cacheConfig)

  private val projectStats = ProjectStatistics(10, 10, Instant.EPOCH)

  private val projectsStatistics: ProjectsStatistics = {
    case `ref` => UIO.some(projectStats)
    case _     => UIO.none
  }

  private lazy val projects     = ProjectsImpl(fetchOrg, Set.empty, defaultApiMappings, projectsConfig, xas)
  private lazy val provisioning = ProjectProvisioning(aclCheck.append, projects, provisioningConfig)
  private lazy val routes       = Route.seal(
    ProjectsRoutes(
      identities,
      aclCheck,
      projects,
      projectsStatistics,
      provisioning,
      DeltaSchemeDirectives.onlyResolveProjUuid(ioFromMap(projectUuid -> ref))
    )
  )

  val desc  = "Project description"
  val base  = "https://localhost/base/"
  val vocab = "https://localhost/voc/"

  val payload = jsonContentOf("/projects/create.json", "description" -> desc, "base" -> base, "vocab" -> vocab)

  val payloadUpdated =
    jsonContentOf("/projects/create.json", "description" -> "New description", "base" -> base, "vocab" -> vocab)

  val anotherPayload = jsonContentOf("/projects/create.json", "description" -> desc)

  "A project route" should {

    "fail to create a project without projects/create permission" in {
      aclCheck.append(AclAddress.Root, Anonymous -> Set(events.read)).accepted
      Put("/v1/projects/org1/proj", payload.toEntity) ~> routes ~> check {
        response.status shouldEqual StatusCodes.Forbidden
        response.asJson shouldEqual jsonContentOf("errors/authorization-failed.json")
      }
    }

    "create a project" in {
      aclCheck
        .append(
          AclAddress.Root,
          Anonymous      -> Set(projectsPermissions.create),
          caller.subject -> Set(projectsPermissions.create)
        )
        .accepted
      Put("/v1/projects/org1/proj", payload.toEntity) ~> routes ~> check {
        status shouldEqual StatusCodes.Created
        val ref = ProjectRef(Label.unsafe("org1"), Label.unsafe("proj"))
        response.asJson should equalIgnoreArrayOrder(
          projectMetadata(ref, "proj", projectUuid, "org1", orgUuid, rev = 1L)
        )
      }
    }

    "create a project with an authenticated user" in {
      Put("/v1/projects/org1/proj2", anotherPayload.toEntity) ~> asAlice ~> routes ~> check {
        status shouldEqual StatusCodes.Created
        val ref = ProjectRef.unsafe("org1", "proj2")
        response.asJson should
          equalIgnoreArrayOrder(
            projectMetadata(
              ref,
              "proj2",
              projectUuid,
              "org1",
              orgUuid,
              rev = 1L,
              createdBy = alice,
              updatedBy = alice
            )
          )
      }
    }

    "reject the creation of a project without a label" in {
      Put("/v1/projects/org1", anotherPayload.toEntity) ~> asAlice ~> routes ~> check {
        status shouldEqual StatusCodes.MethodNotAllowed
      }
    }

    "reject the creation of a project which already exists" in {
      Put("/v1/projects/org1/proj", payload.toEntity) ~> routes ~> check {
        status shouldEqual StatusCodes.Conflict
        response.asJson shouldEqual jsonContentOf(
          "/projects/errors/already-exists.json",
          "org"  -> "org1",
          "proj" -> "proj"
        )
      }
    }

    "reject the creation of a project on a deprecated organization" in {
      Put("/v1/projects/org2/proj3", payload.toEntity) ~> routes ~> check {
        status shouldEqual StatusCodes.BadRequest
        response.asJson shouldEqual jsonContentOf("/projects/errors/org-deprecated.json")
      }
    }

    "fail to update a project without projects/write permission" in {
      aclCheck.delete(AclAddress.Project(Label.unsafe("org1"), Label.unsafe("proj"))).accepted
      Put("/v1/projects/org1/proj?rev=1", payloadUpdated.toEntity) ~> routes ~> check {
        response.status shouldEqual StatusCodes.Forbidden
        response.asJson shouldEqual jsonContentOf("errors/authorization-failed.json")
      }
    }

    "update a project" in {
      aclCheck.append(AclAddress.Root, Anonymous -> Set(projectsPermissions.write)).accepted
      Put("/v1/projects/org1/proj?rev=1", payloadUpdated.toEntity) ~> routes ~> check {

        status shouldEqual StatusCodes.OK
        val ref = ProjectRef.unsafe("org1", "proj")
        response.asJson should equalIgnoreArrayOrder(
          projectMetadata(ref, "proj", projectUuid, "org1", orgUuid, rev = 2L)
        )
      }
    }

    "reject the update of a project without name" in {
      Put("/v1/projects/org1?rev=2", payload.toEntity) ~> routes ~> check {
        status shouldEqual StatusCodes.MethodNotAllowed
      }
    }

    "reject the update of a non-existent project" in {
      Put("/projects/org1/unknown?rev=1", payload.toEntity) ~> routes ~> check {
        status shouldEqual StatusCodes.NotFound
      }
    }

    "reject the update of a project at a non-existent revision" in {
      Put("/v1/projects/org1/proj?rev=42", payloadUpdated.toEntity) ~> routes ~> check {
        status shouldEqual StatusCodes.Conflict
        response.asJson should equalIgnoreArrayOrder(
          jsonContentOf("/projects/errors/incorrect-rev.json", "provided" -> 42L, "expected" -> 2L)
        )
      }
    }

    "fail to deprecate a project without projects/write permission" in {
      aclCheck.subtract(AclAddress.Root, Anonymous -> Set(projectsPermissions.write)).accepted
      Delete("/v1/projects/org1/proj?rev=2") ~> routes ~> check {
        response.status shouldEqual StatusCodes.Forbidden
        response.asJson shouldEqual jsonContentOf("errors/authorization-failed.json")
      }
    }

    "deprecate a project" in {
      aclCheck.append(AclAddress.Root, Anonymous -> Set(projectsPermissions.write)).accepted
      Delete("/v1/projects/org1/proj?rev=2") ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        val ref = ProjectRef(Label.unsafe("org1"), Label.unsafe("proj"))
        response.asJson should equalIgnoreArrayOrder(
          projectMetadata(ref, "proj", projectUuid, "org1", orgUuid, rev = 3L, deprecated = true)
        )
      }
    }

    "reject the deprecation of a project without rev" in {
      Delete("/v1/projects/org1/proj") ~> routes ~> check {
        status shouldEqual StatusCodes.BadRequest
        response.asJson shouldEqual jsonContentOf("/errors/missing-query-param.json", "field" -> "rev")
      }
    }

    "reject the deprecation of a already deprecated project" in {
      Delete("/v1/projects/org1/proj?rev=3") ~> routes ~> check {
        status shouldEqual StatusCodes.BadRequest
        response.asJson shouldEqual jsonContentOf(
          "/projects/errors/project-deprecated.json",
          "org"  -> "org1",
          "proj" -> "proj"
        )
      }
    }

    val fetchProjRev2 = jsonContentOf(
      "/projects/fetch.json",
      "org"               -> "org1",
      "proj"              -> "proj",
      "orgUuid"           -> orgUuid,
      "uuid"              -> projectUuid,
      "rev"               -> 2L,
      "deprecated"        -> false,
      "markedForDeletion" -> false,
      "description"       -> "New description",
      "base"              -> base,
      "vocab"             -> vocab
    )

    val fetchProjRev3 = jsonContentOf(
      "/projects/fetch.json",
      "org"               -> "org1",
      "proj"              -> "proj",
      "orgUuid"           -> orgUuid,
      "uuid"              -> projectUuid,
      "rev"               -> 3L,
      "deprecated"        -> true,
      "markedForDeletion" -> false,
      "description"       -> "New description",
      "base"              -> base,
      "vocab"             -> vocab
    )

    val fetchProj2 = jsonContentOf(
      "/projects/fetch.json",
      "org"               -> "org1",
      "proj"              -> "proj2",
      "orgUuid"           -> orgUuid,
      "uuid"              -> projectUuid,
      "rev"               -> 1L,
      "deprecated"        -> false,
      "markedForDeletion" -> false,
      "description"       -> "Project description",
      "base"              -> "http://localhost/v1/resources/org1/proj2/_/",
      "vocab"             -> "http://localhost/v1/vocabs/org1/proj2/",
      "user"              -> alice.subject,
      "realm"             -> alice.realm
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
          response.status shouldEqual StatusCodes.Forbidden
          response.asJson shouldEqual jsonContentOf("errors/authorization-failed.json")
        }
      }
    }

    "fetch a project" in {
      aclCheck.append(AclAddress.Root, Anonymous -> Set(projectsPermissions.read)).accepted
      Get("/v1/projects/org1/proj") ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson should equalIgnoreArrayOrder(fetchProjRev3)
      }
    }

    "fetch a specific project revision" in {
      Get("/v1/projects/org1/proj?rev=2") ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson should equalIgnoreArrayOrder(fetchProjRev2)
      }
    }

    "fetch a project by uuid" in {
      Get(s"/v1/projects/$orgUuid/$projectUuid") ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson should equalIgnoreArrayOrder(fetchProjRev3)
      }
    }

    "fetch a specific project revision by uuid" in {
      Get(s"/v1/projects/$orgUuid/$projectUuid?rev=2") ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson should equalIgnoreArrayOrder(fetchProjRev2)
      }
    }

    "fetch a project with an incorrect revision" in {
      Get("/v1/projects/org1/proj?rev=42") ~> routes ~> check {
        status shouldEqual StatusCodes.NotFound
        response.asJson shouldEqual jsonContentOf(
          "/errors/revision-not-found.json",
          "provided" -> 42L,
          "current"  -> 3L
        )
      }
    }

    "fetch a project by uuid with an incorrect revision" in {
      Get(s"/v1/projects/$orgUuid/$projectUuid?rev=42") ~> routes ~> check {
        status shouldEqual StatusCodes.NotFound
        response.asJson shouldEqual jsonContentOf(
          "/errors/revision-not-found.json",
          "provided" -> 42L,
          "current"  -> 3L
        )
      }
    }

    "fetch another project" in {
      Get("/v1/projects/org1/proj2") ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson should equalIgnoreArrayOrder(fetchProj2)
      }
    }

    "fetch an unknown project" in {
      Get(s"/v1/projects/org1/unknown") ~> routes ~> check {
        status shouldEqual StatusCodes.NotFound
        response.asJson shouldEqual jsonContentOf("/projects/errors/project-not-found.json", "proj" -> "org1/unknown")
      }
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

    "list all projects" in {
      val expected = expectedResults(fetchProjRev3.removeKeys("@context"), fetchProj2.removeKeys("@context"))
      Get("/v1/projects") ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson should equalIgnoreArrayOrder(expected)
      }
      Get("/v1/projects?label=p") ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson should equalIgnoreArrayOrder(expected)
      }
    }

    "list all projects for organization" in {
      val expected = expectedResults(fetchProjRev3.removeKeys("@context"), fetchProj2.removeKeys("@context"))

      Get("/v1/projects/org1") ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson should equalIgnoreArrayOrder(expected)
      }
      Get("/v1/projects/org1?label=p") ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson should equalIgnoreArrayOrder(expected)
      }
    }

    "list all deprecated projects " in {
      Get("/v1/projects?deprecated=true") ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson should equalIgnoreArrayOrder(
          expectedResults(
            fetchProjRev3.removeKeys("@context")
          )
        )
      }
    }

    "list all projects updated by Alice" in {
      Get(s"/v1/projects?updatedBy=${UrlUtils.encode(alice.asIri.toString)}&label=p") ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson should equalIgnoreArrayOrder(
          expectedResults(
            fetchProj2.removeKeys("@context")
          )
        )
      }
    }

    "list all projects user has access to" in {
      aclCheck.subtract(AclAddress.Root, Anonymous -> Set(projectsPermissions.read)).accepted
      aclCheck.append(ProjectRef.unsafe("org1", "proj"), Anonymous -> Set(projectsPermissions.read)).accepted
      Get("/v1/projects") ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson should equalIgnoreArrayOrder(
          expectedResults(
            fetchProjRev3.removeKeys("@context")
          )
        )
      }
    }

    "fail to get the project statistics without resources/read permission" in {
      aclCheck.subtract(AclAddress.Root, Anonymous -> Set(resources.read)).accepted
      Get("/v1/projects/org1/proj/statistics") ~> routes ~> check {
        response.asJson shouldEqual jsonContentOf("errors/authorization-failed.json")
        response.status shouldEqual StatusCodes.Forbidden
      }
    }

    "fail to get the project statistics for an unknown project" in {
      aclCheck.append(AclAddress.Root, Anonymous -> Set(resources.read)).accepted
      Get("/v1/projects/org1/unknown/statistics") ~> routes ~> check {
        status shouldEqual StatusCodes.NotFound
        response.asJson shouldEqual jsonContentOf("/projects/errors/project-not-found.json", "proj" -> "org1/unknown")
      }
    }

    "get the project statistics" in {
      Get("/v1/projects/org1/proj/statistics") ~> routes ~> check {
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
      Get("/v1/projects/users-org") ~> asProvisioned ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson.asObject.value("_total").value.asNumber.value.toInt.value shouldEqual 1
      }

      Get("/v1/projects/users-org/user1") ~> asProvisioned ~> routes ~> check {
        status shouldEqual StatusCodes.OK
      }
    }

    "return error when failed to provision project" in {
      Get("/v1/projects/users-org") ~> asInvalid ~> routes ~> check {
        status shouldEqual StatusCodes.InternalServerError
      }
    }

    "redirect to fusion for the latest version if the Accept header is set to text/html" in {
      Get("/v1/projects/users-org/user1") ~> Accept(`text/html`) ~> routes ~> check {
        response.status shouldEqual StatusCodes.SeeOther
        response.header[Location].value.uri shouldEqual Uri("https://bbp.epfl.ch/nexus/web/admin/users-org/user1")
      }
    }
  }

  def projectMetadata(
                       ref: ProjectRef,
                       label: String,
                       uuid: UUID,
                       organizationLabel: String,
                       organizationUuid: UUID,
                       rev: Long = 1L,
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
}
