package ai.senscience.nexus.delta.routes

import akka.http.scaladsl.model.MediaTypes.`text/html`
import akka.http.scaladsl.model.headers.{Accept, Location}
import akka.http.scaladsl.model.{StatusCodes, Uri}
import akka.http.scaladsl.server.Route
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.sdk.ScopeInitializer
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclSimpleCheck
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ProjectGen.defaultApiMappings
import ch.epfl.bluebrain.nexus.delta.sdk.identities.IdentitiesDummy
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.implicits.*
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.FetchActiveOrganization
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.model.Organization
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.model.OrganizationRejection.{OrganizationIsDeprecated, OrganizationNotFound}
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions.{projects as projectsPermissions, resources}
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.projects.ProjectScopeResolver.PermissionAccess
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.*
import ch.epfl.bluebrain.nexus.delta.sdk.projects.{ProjectScopeResolver, ProjectsConfig, ProjectsImpl, ProjectsStatistics}
import ch.epfl.bluebrain.nexus.delta.sdk.utils.BaseRouteSpec
import ch.epfl.bluebrain.nexus.delta.sourcing.Scope
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Anonymous, Subject, User}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef}
import ch.epfl.bluebrain.nexus.testkit.scalatest.ProjectMatchers.deprecated
import io.circe.Json
import org.scalactic.source.Position
import org.scalatest.BeforeAndAfterAll

import java.time.Instant
import java.util.UUID
import scala.concurrent.duration.*

class ProjectsRoutesSpec extends BaseRouteSpec with BeforeAndAfterAll {

  implicit override def patienceConfig: PatienceConfig = PatienceConfig(6.seconds, 10.milliseconds)

  private val projectUuid           = UUID.randomUUID()
  implicit private val uuidF: UUIDF = UUIDF.fixed(projectUuid)

  private val orgUuid = UUID.randomUUID()

  private val reader              = User("reader", Label.unsafe(genString()))
  private val singleProjectReader = User("singleProjectReader", Label.unsafe(genString()))
  private val creator             = User("creator", Label.unsafe(genString()))
  private val writer              = User("writer", Label.unsafe(genString()))
  private val deleter             = User("deleter", Label.unsafe(genString()))
  private val statisticsReader    = User("statisticsReader", Label.unsafe(genString()))

  private val org1     = Label.unsafe("org1")
  private val org2     = Label.unsafe("org2")
  private val usersOrg = Label.unsafe("users-org")

  private val ref = ProjectRef.unsafe("org1", "proj")

  private def fetchOrg: FetchActiveOrganization = {
    case `org1`     => IO.pure(Organization(org1, orgUuid, None))
    case `usersOrg` => IO.pure(Organization(usersOrg, orgUuid, None))
    case `org2`     => IO.raiseError(OrganizationIsDeprecated(org2))
    case other      => IO.raiseError(OrganizationNotFound(other))
  }

  implicit private val projectsConfig: ProjectsConfig = ProjectsConfig(eventLogConfig, pagination, deletionConfig)

  private val projectStats = ProjectStatistics(10, 10, Instant.EPOCH)

  private val projectsStatistics: ProjectsStatistics = {
    case `ref` => IO.pure(Some(projectStats))
    case _     => IO.none
  }

  private val identities =
    IdentitiesDummy.fromUsers(reader, singleProjectReader, creator, writer, deleter, statisticsReader)

  private val aclCheck = AclSimpleCheck.unsafe(
    (reader, AclAddress.Root, Set(projectsPermissions.read)),
    (singleProjectReader, AclAddress.Project(ref), Set(projectsPermissions.read)),
    (creator, AclAddress.Root, Set(projectsPermissions.create)),
    (writer, AclAddress.Root, Set(projectsPermissions.write)),
    (deleter, AclAddress.Root, Set(projectsPermissions.delete)),
    (statisticsReader, AclAddress.Root, Set(resources.read))
  )

  private val projectScopeResolver = new ProjectScopeResolver {
    override def apply(scope: Scope, permission: Permission)(implicit caller: Caller): IO[Set[ProjectRef]] = ???

    override def access(scope: Scope, permission: Permission)(implicit
        caller: Caller
    ): IO[ProjectScopeResolver.PermissionAccess] = {
      IO.pure {
        (caller.subject, permission) match {
          case (`reader`, projectsPermissions.read)              => PermissionAccess(projectsPermissions.read, Set(AclAddress.Root))
          case (`singleProjectReader`, projectsPermissions.read) =>
            PermissionAccess(projectsPermissions.read, Set(AclAddress.Project(ref)))
          case (`creator`, projectsPermissions.create)           =>
            PermissionAccess(projectsPermissions.create, Set(AclAddress.Root))
          case (`writer`, projectsPermissions.write)             =>
            PermissionAccess(projectsPermissions.write, Set(AclAddress.Root))
          case (`deleter`, projectsPermissions.delete)           =>
            PermissionAccess(projectsPermissions.delete, Set(AclAddress.Root))
          case (`statisticsReader`, resources.read)              => PermissionAccess(resources.read, Set(AclAddress.Root))
          case (_, permission)                                   => PermissionAccess(permission, Set.empty)
        }
      }

    }
  }

  private lazy val projects =
    ProjectsImpl(
      fetchOrg,
      _ => IO.unit,
      _ => IO.unit,
      ScopeInitializer.noop,
      defaultApiMappings,
      eventLogConfig,
      xas,
      clock
    )

  private lazy val routes = Route.seal(
    ProjectsRoutes(
      identities,
      aclCheck,
      projects,
      projectScopeResolver,
      projectsStatistics
    )
  )

  val desc  = "Project description"
  val base  = "https://localhost/base/"
  val vocab = "https://localhost/voc/"

  private val payload = jsonContentOf("projects/create.json", "description" -> desc, "base" -> base, "vocab" -> vocab)

  private val payloadUpdated =
    jsonContentOf("projects/create.json", "description" -> "New description", "base" -> base, "vocab" -> vocab)

  private val anotherPayload = jsonContentOf("projects/create.json", "description" -> desc)

  "A project route" should {

    "fail to create a project without projects/create permission" in {
      Put("/v1/projects/org1/proj", payload.toEntity) ~> routes ~> check {
        response.shouldBeForbidden
      }
    }

    "create a project" in {
      Put("/v1/projects/org1/proj", payload.toEntity) ~> as(creator) ~> routes ~> check {
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
            createdBy = creator,
            updatedBy = creator
          )
        )
      }
    }

    "reject the creation of a project without a label" in {
      Put("/v1/projects/org1", anotherPayload.toEntity) ~> as(creator) ~> routes ~> check {
        status shouldEqual StatusCodes.MethodNotAllowed
      }
    }

    "reject the creation of a project which already exists" in {
      Put("/v1/projects/org1/proj", payload.toEntity) ~> as(creator) ~> routes ~> check {
        status shouldEqual StatusCodes.Conflict
        response.asJson shouldEqual jsonContentOf(
          "projects/errors/already-exists.json",
          "org"  -> "org1",
          "proj" -> "proj"
        )
      }
    }

    "reject the creation of a project on a deprecated organization" in {
      Put("/v1/projects/org2/proj3", payload.toEntity) ~> as(creator) ~> routes ~> check {
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
      Put("/v1/projects/org1/proj?rev=1", payloadUpdated.toEntity) ~> as(writer) ~> routes ~> check {
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
            updatedBy = writer,
            createdBy = creator
          )
        )
      }
    }

    "reject the update of a project without name" in {
      Put("/v1/projects/org1?rev=2", payload.toEntity) ~> as(writer) ~> routes ~> check {
        status shouldEqual StatusCodes.MethodNotAllowed
      }
    }

    "reject the update of a non-existent project" in {
      Put("/projects/org1/unknown?rev=1", payload.toEntity) ~> as(writer) ~> routes ~> check {
        status shouldEqual StatusCodes.NotFound
      }
    }

    "reject the update of a project at a non-existent revision" in {
      Put("/v1/projects/org1/proj?rev=42", payloadUpdated.toEntity) ~> as(writer) ~> routes ~> check {
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
      Delete("/v1/projects/org1/proj") ~> as(writer) ~> routes ~> check {
        status shouldEqual StatusCodes.BadRequest
        response.asJson shouldEqual jsonContentOf("errors/missing-query-param.json", "field" -> "rev")
      }
      latestRevisionOfProject("org1", "proj") should not(be(deprecated))
    }

    "deprecate a project" in {
      Delete("/v1/projects/org1/proj?rev=2") ~> as(writer) ~> routes ~> check {
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
            createdBy = creator,
            updatedBy = writer
          )
        )
      }
    }

    "reject the deprecation of a already deprecated project" in {
      Delete("/v1/projects/org1/proj?rev=3") ~> as(writer) ~> routes ~> check {
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
      createdBy = Some(creator),
      updatedBy = Some(writer)
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
      createdBy = Some(creator),
      updatedBy = Some(writer)
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
      createdBy = Some(creator),
      updatedBy = Some(creator)
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
      Get("/v1/projects/org1/proj") ~> as(reader) ~> routes ~> check {
        status shouldEqual StatusCodes.OK

        response.asJson should equalIgnoreArrayOrder(fetchProjRev3)
      }
    }

    "fetch a specific project revision" in {
      Get("/v1/projects/org1/proj?rev=2") ~> as(reader) ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson should equalIgnoreArrayOrder(fetchProjRev2)
      }
    }

    "fetch a project with an incorrect revision" in {
      Get("/v1/projects/org1/proj?rev=42") ~> as(reader) ~> routes ~> check {
        status shouldEqual StatusCodes.NotFound
        response.asJson shouldEqual jsonContentOf(
          "errors/revision-not-found.json",
          "provided" -> 42,
          "current"  -> 3
        )
      }
    }

    "fetch an unknown project" in {
      Get(s"/v1/projects/org1/unknown") ~> as(reader) ~> routes ~> check {
        status shouldEqual StatusCodes.NotFound
        response.asJson shouldEqual jsonContentOf("projects/errors/project-not-found.json", "proj" -> "org1/unknown")
      }
    }

    "list all projects" in {
      Put("/v1/projects/org1/proj2", anotherPayload.toEntity) ~> as(creator) ~> routes ~> check {
        status shouldEqual StatusCodes.Created
      }

      val expected = expectedResults(fetchProjRev3.removeKeys("@context"), fetchProj2.removeKeys("@context"))
      Get("/v1/projects") ~> as(reader) ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson should equalIgnoreArrayOrder(expected)
      }
      Get("/v1/projects?label=p") ~> as(reader) ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson should equalIgnoreArrayOrder(expected)
      }
    }

    "list all projects for organization" in {
      val expected = expectedResults(fetchProjRev3.removeKeys("@context"), fetchProj2.removeKeys("@context"))

      Get("/v1/projects/org1") ~> as(reader) ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson should equalIgnoreArrayOrder(expected)
      }
      Get("/v1/projects/org1?label=p") ~> as(reader) ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson should equalIgnoreArrayOrder(expected)
      }
    }

    "list all deprecated projects " in {
      Get("/v1/projects?deprecated=true") ~> as(reader) ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson should equalIgnoreArrayOrder(
          expectedResults(
            fetchProjRev3.removeKeys("@context")
          )
        )
      }
    }

    "list all projects user has access to" in {
      Get("/v1/projects") ~> as(singleProjectReader) ~> routes ~> check {
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
      Get("/v1/projects/org1/unknown/statistics") ~> as(statisticsReader) ~> routes ~> check {
        status shouldEqual StatusCodes.NotFound
        response.asJson shouldEqual jsonContentOf("projects/errors/project-not-found.json", "proj" -> "org1/unknown")
      }
    }

    "get the project statistics" in {
      Get("/v1/projects/org1/proj/statistics") ~> as(statisticsReader) ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson shouldEqual json"""{
          "@context" : "https://bluebrain.github.io/nexus/contexts/statistics.json",
          "lastProcessedEventDateTime" : "1970-01-01T00:00:00Z",
          "eventsCount" : 10,
          "resourcesCount" : 10
        }"""
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
      Delete("/v1/projects/org1/proj?rev=3&prune=true") ~> as(deleter) ~> routes ~> check {
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
            createdBy = creator,
            updatedBy = deleter
          )
        )
      }
    }

    "fail to undeprecate a project without projects/write permission" in {
      val (org, project) = thereIsADeprecatedProject
      Put(s"/v1/projects/$org/$project/undeprecate?rev=2") ~> as(reader) ~> routes ~> check {
        response.shouldBeForbidden
      }
      latestRevisionOfProject(org, project) should be(deprecated)
    }

    "fail to undeprecate a project if it is not deprecated" in {
      val (org, project) = thereIsAProject
      Put(s"/v1/projects/$org/$project/undeprecate?rev=1") ~> as(writer) ~> routes ~> check {
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

      Put(s"/v1/projects/$org/$project/undeprecate?rev=2") ~> as(writer) ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson should not(be(deprecated))
      }

      latestRevisionOfProject(org, project) should not(be(deprecated))
    }
  }

  private def thereIsAProject = {
    val org     = org1.value
    val project = genString()
    Put(s"/v1/projects/$org/$project", payload.toEntity) ~> as(creator) ~> routes ~> check {
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
    Delete(s"/v1/projects/$org/$project?rev=$rev") ~> as(writer) ~> routes ~> check {
      status shouldEqual StatusCodes.OK
      response.asJson should be(deprecated)
    }
    ()
  }

  private def latestRevisionOfProject(org: String, project: String): Json = {
    Get(s"/v1/projects/$org/$project") ~> as(reader) ~> routes ~> check {
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
  ): Json           =
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
      "_results" -> Json.arr(results*)
    )
}
