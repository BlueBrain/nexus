package ch.epfl.bluebrain.nexus.admin.routes

import java.time.Instant
import java.util.UUID
import java.util.regex.Pattern.quote

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.{BasicHttpCredentials, OAuth2BearerToken}
import akka.http.scaladsl.testkit.ScalatestRouteTest
import ch.epfl.bluebrain.nexus.admin.Error
import ch.epfl.bluebrain.nexus.admin.Error._
import ch.epfl.bluebrain.nexus.admin.config.Permissions
import ch.epfl.bluebrain.nexus.admin.index.{OrganizationCache, ProjectCache}
import ch.epfl.bluebrain.nexus.admin.organizations.Organization
import ch.epfl.bluebrain.nexus.admin.projects.ProjectRejection._
import ch.epfl.bluebrain.nexus.admin.projects.{Project, ProjectDescription, Projects}
import ch.epfl.bluebrain.nexus.admin.routes.SearchParams.Field
import ch.epfl.bluebrain.nexus.admin.types.ResourceF
import ch.epfl.bluebrain.nexus.commons.search.FromPagination
import ch.epfl.bluebrain.nexus.commons.search.QueryResult.UnscoredQueryResult
import ch.epfl.bluebrain.nexus.commons.search.QueryResults.UnscoredQueryResults
import ch.epfl.bluebrain.nexus.iam.acls.{AccessControlList, AccessControlLists, Acls}
import ch.epfl.bluebrain.nexus.iam.auth.AccessToken
import ch.epfl.bluebrain.nexus.iam.realms.Realms
import ch.epfl.bluebrain.nexus.iam.types.Identity.{Anonymous, Subject, User}
import ch.epfl.bluebrain.nexus.iam.types.{Caller, Permission, ResourceF => IamResourceF}
import ch.epfl.bluebrain.nexus.rdf.Iri
import ch.epfl.bluebrain.nexus.rdf.Iri.Path
import ch.epfl.bluebrain.nexus.rdf.Iri.Path._
import ch.epfl.bluebrain.nexus.rdf.implicits._
import ch.epfl.bluebrain.nexus.delta.config.AppConfig.{HttpConfig, PaginationConfig}
import ch.epfl.bluebrain.nexus.delta.config.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.config.{AppConfig, Settings}
import ch.epfl.bluebrain.nexus.delta.marshallers.instances._
import ch.epfl.bluebrain.nexus.delta.routes.Routes
import ch.epfl.bluebrain.nexus.util.{EitherValues, Resources}
import io.circe.Json
import monix.eval.Task
import monix.execution.Scheduler.global
import org.mockito.{ArgumentMatchersSugar, IdiomaticMockito}
import org.scalatest.Inspectors
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

//noinspection TypeAnnotation
class ProjectRoutesSpec
    extends AnyWordSpecLike
    with IdiomaticMockito
    with ArgumentMatchersSugar
    with ScalatestRouteTest
    with ScalaFutures
    with EitherValues
    with Resources
    with Matchers
    with Inspectors {

  private val orgCache  = mock[OrganizationCache[Task]]
  private val projCache = mock[ProjectCache[Task]]
  private val projects  = mock[Projects[Task]]
  private val aclsApi   = mock[Acls[Task]]
  private val realmsApi = mock[Realms[Task]]

  private val config: AppConfig               =
    Settings(system).appConfig.copy(http = HttpConfig("some", 80, "v1", "https://nexus.example.com"))
  implicit private val httpConfig: HttpConfig = config.http

  private val routes =
    Routes.wrap(
      new ProjectRoutes(projects, orgCache, projCache, aclsApi, realmsApi)(
        httpConfig,
        PaginationConfig(50, 50, 100),
        global
      ).routes
    )

  //noinspection TypeAnnotation
  trait Context {
    implicit val caller: Caller   = Caller(User("realm", "alice"), Set.empty)
    implicit val subject: Subject = caller.subject

    val acls: AccessControlLists = AccessControlLists(
      Path./ -> IamResourceF(
        url"http://localhost/",
        1L,
        Set.empty,
        Instant.EPOCH,
        Anonymous,
        Instant.EPOCH,
        Anonymous,
        AccessControlList(Anonymous -> Set(Permissions.projects.read))
      )
    )

    val create = Permission.unsafe("projects/create")
    val read   = Permission.unsafe("projects/read")
    val write  = Permission.unsafe("projects/write")
    val cred   = OAuth2BearerToken("token")
    val token  = AccessToken(cred.token)

    val instant = Instant.now
    val types   = Set(nxv.Project.value)
    val desc    = Some("Project description")
    val orgId   = UUID.randomUUID
    val projId  = UUID.randomUUID
    val base    = url"https://nexus.example.com/base"
    val voc     = url"https://nexus.example.com/voc"
    val iri     = url"http://nexus.example.com/v1/projects/org/label"

    val payload      = Json.obj(
      "description" -> Json.fromString("Project description"),
      "base"        -> Json.fromString("https://nexus.example.com/base"),
      "vocab"       -> Json.fromString("https://nexus.example.com/voc"),
      "apiMappings" -> Json.arr(
        Json.obj(
          "prefix"    -> Json.fromString("nxv"),
          "namespace" -> Json.fromString("https://bluebrain.github.io/nexus/vocabulary/")
        ),
        Json.obj(
          "prefix"    -> Json.fromString("rdf"),
          "namespace" -> Json.fromString("http://www.w3.org/1999/02/22-rdf-syntax-ns#")
        )
      )
    )
    val organization = ResourceF(
      url"http://nexus.example.com/v1/orgs/org",
      orgId,
      1L,
      deprecated = false,
      Set(nxv.Organization.value),
      instant,
      caller.subject,
      instant,
      caller.subject,
      Organization("org", Some("Org description"))
    )
    val mappings     = Map(
      "nxv" -> url"https://bluebrain.github.io/nexus/vocabulary/",
      "rdf" -> url"http://www.w3.org/1999/02/22-rdf-syntax-ns#"
    )
    val project      = ProjectDescription(desc, mappings, Some(base), Some(voc))
    val resource     =
      ResourceF(
        iri,
        projId,
        1L,
        deprecated = false,
        types,
        instant,
        caller.subject,
        instant,
        caller.subject,
        Project("label", orgId, "org", desc, mappings, base, voc)
      )
    val meta         = resource.discard
    val replacements = Map(
      quote("{instant}") -> instant.toString,
      quote("{uuid}")    -> projId.toString,
      quote("{orgUuid}") -> orgId.toString
    )

    realmsApi.caller(token) shouldReturn Task(caller)

    val parent = Path("/org").rightValue

    aclsApi.list(parent, ancestors = true, self = true)(caller) shouldReturn
      Task.pure(
        AccessControlLists(
          parent -> IamResourceF(
            url"http://nexus.example.com/$parent",
            1L,
            Set.empty,
            Instant.now(),
            subject,
            Instant.now(),
            subject,
            AccessControlList(subject -> Set(create, write, read))
          )
        )
      )

    aclsApi.list("org" / "label", ancestors = true, self = true)(caller) shouldReturn
      Task.pure(
        AccessControlLists(
          "org" / "label" -> IamResourceF(
            url"http://nexus.example.com/${"org" / "label"}",
            1L,
            Set.empty,
            Instant.now(),
            subject,
            Instant.now(),
            subject,
            AccessControlList(subject -> Set(create, write, read))
          )
        )
      )
    aclsApi.hasPermission(/ + "org", create)(caller) shouldReturn Task.pure(true)
    aclsApi.hasPermission("org" / "label", create)(caller) shouldReturn Task.pure(true)
    aclsApi.hasPermission("org" / "label", write)(caller) shouldReturn Task.pure(true)
    aclsApi.hasPermission("org" / "label", read)(caller) shouldReturn Task.pure(true)

  }

  "Project routes" should {

    "create a project" in new Context {
      projects.create("org", "label", project) shouldReturn Task(Right(meta))

      Put("/projects/org/label", payload) ~> addCredentials(cred) ~> routes ~> check {
        status shouldEqual StatusCodes.Created
        responseAs[Json] shouldEqual jsonContentOf("/projects/meta.json", replacements)
      }
    }

    "create a project without optional fields" in new Context {
      projects.create("org", "label", ProjectDescription(None, Map.empty, None, None)) shouldReturn Task(Right(meta))

      Put("/projects/org/label", Json.obj()) ~> addCredentials(cred) ~> routes ~> check {
        status shouldEqual StatusCodes.Created
        responseAs[Json] shouldEqual jsonContentOf("/projects/meta.json", replacements)
      }
    }

    "reject the creation of a project without a label" in new Context {

      Put("/projects/org", payload) ~> addCredentials(cred) ~> routes ~> check {
        status shouldEqual StatusCodes.MethodNotAllowed
        responseAs[Error].`@type` shouldEqual "HttpMethodNotAllowed"
      }
    }

    "reject the creation of a project which already exists" in new Context {
      projects.create("org", "label", project) shouldReturn Task(Left(ProjectAlreadyExists("org", "label")))

      Put("/projects/org/label", payload) ~> addCredentials(cred) ~> routes ~> check {
        status shouldEqual StatusCodes.Conflict
        responseAs[Error].`@type` shouldEqual classNameOf[ProjectAlreadyExists.type]
      }
    }

    "update a project" in new Context {
      projects.update("org", "label", project, 2L) shouldReturn Task(Right(meta))

      Put("/projects/org/label?rev=2", payload) ~> addCredentials(cred) ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        responseAs[Json] shouldEqual jsonContentOf("/projects/meta.json", replacements)
      }
    }

    "reject the update of a project without name" in new Context {
      Put("/projects/org?rev=2", payload) ~> addCredentials(cred) ~> routes ~> check {
        status shouldEqual StatusCodes.MethodNotAllowed
        responseAs[Error].`@type` shouldEqual "HttpMethodNotAllowed"
      }
    }

    "reject the update of a non-existent project" in new Context {
      projects.update("org", "label", project, 2L) shouldReturn Task(Left(ProjectNotFound("org", "label")))

      Put("/projects/org/label?rev=2", payload) ~> addCredentials(cred) ~> routes ~> check {
        status shouldEqual StatusCodes.NotFound
        responseAs[Error].`@type` shouldEqual classNameOf[ProjectNotFound.type]
      }
    }

    "reject the update of a non-existent project revision" in new Context {
      projects.update("org", "label", project, 2L) shouldReturn Task(Left(IncorrectRev(1L, 2L)))

      Put("/projects/org/label?rev=2", payload) ~> addCredentials(cred) ~> routes ~> check {
        status shouldEqual StatusCodes.Conflict
        responseAs[Error].`@type` shouldEqual classNameOf[IncorrectRev.type]
      }
    }

    "deprecate a project" in new Context {
      projects.deprecate("org", "label", 2L) shouldReturn Task(Right(meta))

      Delete("/projects/org/label?rev=2") ~> addCredentials(cred) ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        responseAs[Json] shouldEqual jsonContentOf("/projects/meta.json", replacements)
      }
    }

    "reject the deprecation of a project without rev" in new Context {
      Delete("/projects/org/label") ~> addCredentials(cred) ~> routes ~> check {
        status shouldEqual StatusCodes.BadRequest
        responseAs[Error].`@type` shouldEqual "MissingQueryParam"
      }
    }

    "reject the deprecation of a non-existent project" in new Context {
      projects.deprecate("org", "label", 2L) shouldReturn Task(Left(ProjectNotFound("org", "label")))

      Delete("/projects/org/label?rev=2") ~> addCredentials(cred) ~> routes ~> check {
        status shouldEqual StatusCodes.NotFound
        responseAs[Error].`@type` shouldEqual classNameOf[ProjectNotFound.type]
      }
    }

    "fetch a project" in new Context {
      projects.fetch("org", "label") shouldReturn Task(Some(resource))
      projCache.get(resource.value.organizationUuid, resource.uuid) shouldReturn Task(Some(resource))
      val endpoints = List("/projects/org/label", s"/projects/${resource.value.organizationUuid}/${resource.uuid}")
      forAll(endpoints) { endpoint =>
        Get(endpoint) ~> addCredentials(cred) ~> routes ~> check {
          status shouldEqual StatusCodes.OK
          responseAs[Json] shouldEqual jsonContentOf("/projects/resource.json", replacements)
        }
      }
    }

    "return not found for a non-existent project" in new Context {
      projects.fetch("org", "label") shouldReturn Task(None)

      Get("/projects/org/label") ~> addCredentials(cred) ~> routes ~> check {
        status shouldEqual StatusCodes.NotFound
      }
    }

    "fetch a specific project revision" in new Context {
      projects.fetch("org", "label", 2L) shouldReturn Task(Right(resource))

      Get("/projects/org/label?rev=2") ~> addCredentials(cred) ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        responseAs[Json] shouldEqual jsonContentOf("/projects/resource.json", replacements)
      }
    }

    "return not found for a non-existent project revision" in new Context {
      projects.fetch("org", "label", 2L) shouldReturn Task(Left(ProjectNotFound("org", "label")))

      Get("/projects/org/label?rev=2") ~> addCredentials(cred) ~> routes ~> check {
        status shouldEqual StatusCodes.NotFound
      }
    }

    "list all projects" in new Context {
      aclsApi.list("*" / "*", ancestors = true, self = true)(caller) shouldReturn Task.pure(acls)
      val projs = List(1, 2, 3).map { i =>
        val iri = Iri.Url(s"http://nexus.example.com/v1/projects/org/label$i").rightValue
        UnscoredQueryResult(resource.copy(id = iri, value = resource.value.copy(label = s"label$i")))
      }
      projects.list(SearchParams.empty, FromPagination(0, 50))(acls) shouldReturn Task(UnscoredQueryResults(3, projs))

      forAll(List("/projects", "/projects/")) { endpoint =>
        Get(endpoint) ~> addCredentials(cred) ~> routes ~> check {
          status shouldEqual StatusCodes.OK
          responseAs[Json] shouldEqual jsonContentOf("/projects/listing.json", replacements)
        }
      }
    }

    "list deprecated projects on an organization with revision 1" in new Context {
      aclsApi.list("*" / "*", ancestors = true, self = true)(caller) shouldReturn Task.pure(acls)
      val projs = List(1, 2, 3).map { i =>
        val iri = Iri.Url(s"http://nexus.example.com/v1/projects/org/label$i").rightValue
        UnscoredQueryResult(resource.copy(id = iri, value = resource.value.copy(label = s"label$i")))
      }
      projects.list(
        SearchParams(Some(Field("org", exactMatch = true)), deprecated = Some(true), rev = Some(1L)),
        FromPagination(0, 50)
      )(acls) shouldReturn Task(UnscoredQueryResults(3, projs))

      forAll(List("/projects/org?deprecated=true&rev=1", "/projects/org/?deprecated=true&rev=1")) { endpoint =>
        Get(endpoint) ~> addCredentials(cred) ~> routes ~> check {
          status shouldEqual StatusCodes.OK
          responseAs[Json] shouldEqual jsonContentOf("/projects/listing.json", replacements)
        }
      }
    }

    "reject unauthorized requests" in new Context {
      aclsApi.hasPermission("org" / "label", read)(Caller.anonymous) shouldReturn Task.pure(false)

      aclsApi.list("org" / "label", ancestors = true, self = true)(Caller.anonymous) shouldReturn
        Task.pure(AccessControlLists.empty)
      Get("/projects/org/label") ~> routes ~> check {
        status shouldEqual StatusCodes.Forbidden
        responseAs[Error].`@type` shouldEqual "AuthorizationFailed"
      }
    }

    "reject unsupported credentials" in new Context {
      Get("/projects/org/label") ~> addCredentials(BasicHttpCredentials("something")) ~> routes ~> check {
        status shouldEqual StatusCodes.Unauthorized
        responseAs[Error].`@type` shouldEqual "AuthenticationFailed"
      }
    }

    "reject unsupported methods" in new Context {
      Options("/projects/org/label") ~> addCredentials(cred) ~> routes ~> check {
        status shouldEqual StatusCodes.MethodNotAllowed
        responseAs[Error].`@type` shouldEqual "HttpMethodNotAllowed"
      }
    }
  }
}
