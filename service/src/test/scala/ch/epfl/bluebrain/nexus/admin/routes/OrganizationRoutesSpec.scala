package ch.epfl.bluebrain.nexus.admin.routes

import java.time.Instant
import java.util.UUID
import java.util.regex.Pattern.quote

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.{BasicHttpCredentials, OAuth2BearerToken}
import akka.http.scaladsl.testkit.ScalatestRouteTest
import ch.epfl.bluebrain.nexus.admin.Error
import ch.epfl.bluebrain.nexus.admin.Error.classNameOf
import ch.epfl.bluebrain.nexus.admin.config.AppConfig.{HttpConfig, PaginationConfig}
import ch.epfl.bluebrain.nexus.admin.config.Permissions.orgs._
import ch.epfl.bluebrain.nexus.admin.config.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.admin.config.{AppConfig, Permissions, Settings}
import ch.epfl.bluebrain.nexus.admin.index.OrganizationCache
import ch.epfl.bluebrain.nexus.admin.marshallers.instances._
import ch.epfl.bluebrain.nexus.admin.organizations.OrganizationRejection._
import ch.epfl.bluebrain.nexus.admin.organizations.{Organization, Organizations}
import ch.epfl.bluebrain.nexus.admin.types.ResourceF
import ch.epfl.bluebrain.nexus.commons.search.FromPagination
import ch.epfl.bluebrain.nexus.commons.test.{EitherValues, Resources}
import ch.epfl.bluebrain.nexus.commons.search.QueryResult.UnscoredQueryResult
import ch.epfl.bluebrain.nexus.commons.search.QueryResults.UnscoredQueryResults
import ch.epfl.bluebrain.nexus.iam.client.IamClient
import ch.epfl.bluebrain.nexus.iam.client.config.IamClientConfig
import ch.epfl.bluebrain.nexus.iam.client.types.Identity.Anonymous
import ch.epfl.bluebrain.nexus.iam.client.types._
import ch.epfl.bluebrain.nexus.rdf.Iri
import ch.epfl.bluebrain.nexus.rdf.Iri.Path
import ch.epfl.bluebrain.nexus.rdf.implicits._
import io.circe.Json
import monix.eval.Task
import monix.execution.Scheduler.global
import org.mockito.{ArgumentMatchersSugar, IdiomaticMockito}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.Inspectors
import org.scalatest.matchers.should.Matchers

//noinspection TypeAnnotation
class OrganizationRoutesSpec
    extends AnyWordSpecLike
    with IdiomaticMockito
    with ArgumentMatchersSugar
    with ScalatestRouteTest
    with ScalaFutures
    with EitherValues
    with Resources
    with Matchers
    with Inspectors {

  private val iamClient         = mock[IamClient[Task]]
  private val organizationCache = mock[OrganizationCache[Task]]
  private val organizations     = mock[Organizations[Task]]

  private val appConfig: AppConfig            = Settings(system).appConfig
  private implicit val httpConfig: HttpConfig = appConfig.http
  private implicit val iamClientConfig: IamClientConfig = IamClientConfig(
    url"https://nexus.example.com",
    url"http://localhost:8080",
    "v1"
  )

  private val routes =
    Routes.wrap(
      OrganizationRoutes(organizations)(
        iamClient,
        organizationCache,
        iamClientConfig,
        httpConfig,
        PaginationConfig(50, 100),
        global
      ).routes
    )

  //noinspection TypeAnnotation
  trait Context {
    implicit val caller: Caller            = Caller(Identity.User("realm", "alice"), Set.empty)
    implicit val subject: Identity.Subject = caller.subject
    implicit val token: Some[AuthToken]    = Some(AuthToken("token"))

    val acls: AccessControlLists = AccessControlLists(
      Path./ -> ResourceAccessControlList(
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

    val cred = OAuth2BearerToken("token")

    val instant = Instant.now
    val types   = Set(nxv.Organization.value)
    val orgId   = UUID.randomUUID
    val iri     = url"http://nexus.example.com/v1/orgs/org"
    val path    = Path("/org").rightValue

    val description  = Json.obj("description" -> Json.fromString("Org description"))
    val organization = Organization("org", Some("Org description"))
    val resource = ResourceF(
      iri,
      orgId,
      1L,
      deprecated = false,
      types,
      instant,
      caller.subject,
      instant,
      caller.subject,
      organization
    )
    val meta         = resource.discard
    val replacements = Map(quote("{instant}") -> instant.toString, quote("{uuid}") -> orgId.toString)
  }

  "Organizations routes" should {

    "create an organization" in new Context {
      iamClient.hasPermission(path, create)(any[Option[AuthToken]]) shouldReturn Task.pure(true)
      iamClient.identities shouldReturn Task(caller)
      organizations.create(organization) shouldReturn Task(Right(meta))

      Put("/orgs/org", description) ~> addCredentials(cred) ~> routes ~> check {
        status shouldEqual StatusCodes.Created
        responseAs[Json].spaces2 shouldEqual jsonContentOf("/orgs/meta.json", replacements).spaces2
      }
    }

    "create an organization without payload" in new Context {
      iamClient.hasPermission(path, create)(any[Option[AuthToken]]) shouldReturn Task.pure(true)
      iamClient.identities shouldReturn Task(caller)
      organizations.create(organization.copy(description = None)) shouldReturn Task(Right(meta))

      Put("/orgs/org") ~> addCredentials(cred) ~> routes ~> check {
        status shouldEqual StatusCodes.Created
        responseAs[Json].spaces2 shouldEqual jsonContentOf("/orgs/meta.json", replacements).spaces2
      }
    }

    "create an organization with empty payload" in new Context {
      iamClient.hasPermission(path, create)(any[Option[AuthToken]]) shouldReturn Task.pure(true)
      iamClient.identities shouldReturn Task(caller)
      organizations.create(organization.copy(description = None)) shouldReturn Task(Right(meta))

      Put("/orgs/org", Json.obj()) ~> addCredentials(cred) ~> routes ~> check {
        status shouldEqual StatusCodes.Created
        responseAs[Json].spaces2 shouldEqual jsonContentOf("/orgs/meta.json", replacements).spaces2
      }
    }

    "updates an organization" in new Context {
      iamClient.hasPermission(path, write)(any[Option[AuthToken]]) shouldReturn Task.pure(true)
      iamClient.identities shouldReturn Task(caller)
      organizations.update(organization.label, organization, 2L) shouldReturn Task(Right(meta))

      Put("/orgs/org?rev=2", description) ~> addCredentials(cred) ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        responseAs[Json].spaces2 shouldEqual jsonContentOf("/orgs/meta.json", replacements).spaces2
      }
    }

    "updates an organization with empty payload" in new Context {
      iamClient.hasPermission(path, write)(any[Option[AuthToken]]) shouldReturn Task.pure(true)
      iamClient.identities shouldReturn Task(caller)
      organizations.update(organization.label, organization.copy(description = None), 1L) shouldReturn Task(Right(meta))

      Put("/orgs/org?rev=1", Json.obj()) ~> addCredentials(cred) ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        responseAs[Json].spaces2 shouldEqual jsonContentOf("/orgs/meta.json", replacements).spaces2
      }
    }

    "reject the creation of an organization which already exists" in new Context {
      iamClient.hasPermission(path, create)(any[Option[AuthToken]]) shouldReturn Task.pure(true)
      iamClient.identities shouldReturn Task(caller)
      organizations.create(organization) shouldReturn Task(Left(OrganizationAlreadyExists("org")))

      Put("/orgs/org", description) ~> addCredentials(cred) ~> routes ~> check {
        status shouldEqual StatusCodes.Conflict
        responseAs[Error].`@type` shouldEqual classNameOf[OrganizationAlreadyExists.type]
      }
    }

    "reject the creation of an organization without a label" in new Context {
      iamClient.hasPermission(Path./, create)(any[Option[AuthToken]]) shouldReturn Task.pure(true)
      iamClient.identities shouldReturn Task(caller)

      Put("/orgs/", description) ~> addCredentials(cred) ~> routes ~> check {
        status shouldEqual StatusCodes.MethodNotAllowed
        responseAs[Error].`@type` shouldEqual "HttpMethodNotAllowed"
      }
    }

    "fetch an organization" in new Context {
      iamClient.hasPermission(path, read)(any[Option[AuthToken]]) shouldReturn Task.pure(true)
      iamClient.identities shouldReturn Task(caller)
      organizations.fetch("org") shouldReturn Task(Some(resource))
      organizationCache.get(resource.uuid) shouldReturn Task(Some(resource))
      val endpoints = List("/orgs/org", s"/orgs/${resource.uuid}")
      forAll(endpoints) { endpoint =>
        Get(endpoint) ~> addCredentials(cred) ~> routes ~> check {
          status shouldEqual StatusCodes.OK
          responseAs[Json].spaces2 shouldEqual jsonContentOf("/orgs/resource.json", replacements).spaces2
        }
      }
    }

    "return not found for a non-existent organization" in new Context {
      iamClient.hasPermission(path, read)(any[Option[AuthToken]]) shouldReturn Task.pure(true)
      iamClient.identities shouldReturn Task(caller)
      organizations.fetch("org") shouldReturn Task(None)

      Get("/orgs/org") ~> addCredentials(cred) ~> routes ~> check {
        status shouldEqual StatusCodes.NotFound
      }
    }

    "fetch a specific organization revision" in new Context {
      iamClient.hasPermission(path, read)(any[Option[AuthToken]]) shouldReturn Task.pure(true)
      iamClient.identities shouldReturn Task(caller)
      organizations.fetch("org", Some(2L)) shouldReturn Task(Some(resource))

      Get("/orgs/org?rev=2") ~> addCredentials(cred) ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        responseAs[Json].spaces2 shouldEqual jsonContentOf("/orgs/resource.json", replacements).spaces2
      }
    }

    "deprecate an organization" in new Context {
      iamClient.hasPermission(path, write)(any[Option[AuthToken]]) shouldReturn Task.pure(true)
      iamClient.identities shouldReturn Task(caller)
      organizations.deprecate("org", 2L) shouldReturn Task(Right(meta))

      Delete("/orgs/org?rev=2") ~> addCredentials(cred) ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        responseAs[Json].spaces2 shouldEqual jsonContentOf("/orgs/meta.json", replacements).spaces2
      }
    }

    "reject the deprecation of an organization without rev" in new Context {
      iamClient.hasPermission(path, write)(any[Option[AuthToken]]) shouldReturn Task.pure(true)
      iamClient.identities shouldReturn Task(caller)

      Delete("/orgs/org") ~> addCredentials(cred) ~> routes ~> check {
        status shouldEqual StatusCodes.BadRequest
        responseAs[Error].`@type` shouldEqual "MissingQueryParam"
      }
    }

    "reject the deprecation of an organization with incorrect rev" in new Context {
      iamClient.hasPermission(path, write)(any[Option[AuthToken]]) shouldReturn Task.pure(true)
      iamClient.identities shouldReturn Task(caller)
      organizations.deprecate("org", 2L) shouldReturn Task(Left(IncorrectRev(3L, 2L)))

      Delete("/orgs/org?rev=2") ~> addCredentials(cred) ~> routes ~> check {
        status shouldEqual StatusCodes.Conflict
        responseAs[Error].`@type` shouldEqual classNameOf[IncorrectRev.type]
      }
    }

    "reject unauthorized requests" in new Context {
      iamClient.hasPermission(path, read)(None) shouldReturn Task.pure(false)
      iamClient.identities(None) shouldReturn Task(Caller.anonymous)

      Get("/orgs/org") ~> routes ~> check {
        status shouldEqual StatusCodes.Forbidden
        responseAs[Error].`@type` shouldEqual "AuthorizationFailed"
      }
    }

    "reject wrong endpoint" in new Context {
      Get("/other") ~> routes ~> check {
        status shouldEqual StatusCodes.NotFound
        responseAs[Error].`@type` shouldEqual "NotFound"
      }
    }

    "list organizations" in new Context {
      iamClient.hasPermission(Path./, read)(any[Option[AuthToken]]) shouldReturn Task.pure(true)
      iamClient.hasPermission(Path.Empty, read)(any[Option[AuthToken]]) shouldReturn Task.pure(true)
      iamClient.identities shouldReturn Task(caller)
      iamClient.acls(Path("/*").rightValue, ancestors = true, self = true) shouldReturn Task(acls)

      val orgs = List(1, 2, 3).map { i =>
        val iri = Iri.Url(s"http://nexus.example.com/v1/orgs/org$i").rightValue
        UnscoredQueryResult(resource.copy(id = iri, value = resource.value.copy(label = s"org$i")))
      }
      organizations.list(SearchParams.empty, FromPagination(0, 50))(acls) shouldReturn Task(
        UnscoredQueryResults(3, orgs)
      )

      forAll(List("/orgs", "/orgs/")) { endpoint =>
        Get(endpoint) ~> addCredentials(cred) ~> routes ~> check {
          status shouldEqual StatusCodes.OK
          responseAs[Json].spaces2 shouldEqual jsonContentOf("/orgs/listing.json", replacements).spaces2
        }
      }
    }

    "list deprecated organizations" in new Context {
      iamClient.hasPermission(Path./, read)(any[Option[AuthToken]]) shouldReturn Task.pure(true)
      iamClient.hasPermission(Path.Empty, read)(any[Option[AuthToken]]) shouldReturn Task.pure(true)
      iamClient.identities shouldReturn Task(caller)
      iamClient.acls(Path("/*").rightValue, ancestors = true, self = true) shouldReturn Task(acls)

      val orgs = List(1, 2, 3).map { i =>
        val iri = Iri.Url(s"http://nexus.example.com/v1/orgs/org$i").rightValue
        UnscoredQueryResult(resource.copy(id = iri, value = resource.value.copy(label = s"org$i")))
      }
      organizations.list(SearchParams(deprecated = Some(true)), FromPagination(0, 50))(acls) shouldReturn Task(
        UnscoredQueryResults(3, orgs)
      )

      forAll(List("/orgs?deprecated=true", "/orgs/?deprecated=true")) { endpoint =>
        Get(endpoint) ~> addCredentials(cred) ~> routes ~> check {
          status shouldEqual StatusCodes.OK
          responseAs[Json].spaces2 shouldEqual jsonContentOf("/orgs/listing.json", replacements).spaces2
        }
      }
    }

    "reject unsupported credentials" in new Context {
      Get("/orgs/org") ~> addCredentials(BasicHttpCredentials("something")) ~> routes ~> check {
        status shouldEqual StatusCodes.Unauthorized
        responseAs[Error].`@type` shouldEqual "AuthenticationFailed"
      }
    }

    "reject unsupported methods" in new Context {
      Options("/orgs/org") ~> addCredentials(cred) ~> routes ~> check {
        status shouldEqual StatusCodes.MethodNotAllowed
        responseAs[Error].`@type` shouldEqual "HttpMethodNotAllowed"
      }
    }
  }
}
