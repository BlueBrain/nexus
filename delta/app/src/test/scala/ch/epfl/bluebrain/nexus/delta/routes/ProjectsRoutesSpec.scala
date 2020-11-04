package ch.epfl.bluebrain.nexus.delta.routes

import java.util.UUID

import akka.http.scaladsl.model.MediaRanges.`*/*`
import akka.http.scaladsl.model.MediaTypes.`text/event-stream`
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.{`Last-Event-ID`, Accept, OAuth2BearerToken}
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.testkit.ScalatestRouteTest
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UrlUtils
import ch.epfl.bluebrain.nexus.delta.sdk.model.Label
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.{Anonymous, Authenticated, Group}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.{AuthToken, Caller, Identity}
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.{IdentitiesDummy, OrganizationsDummy, ProjectsDummy}
import ch.epfl.bluebrain.nexus.delta.sdk.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.utils.{RouteFixtures, RouteHelpers}
import ch.epfl.bluebrain.nexus.delta.syntax._
import ch.epfl.bluebrain.nexus.testkit._
import io.circe.Json
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.{Inspectors, OptionValues}

class ProjectsRoutesSpec
    extends AnyWordSpecLike
    with ScalatestRouteTest
    with Matchers
    with CirceLiteral
    with CirceEq
    with DeltaDirectives
    with IOFixedClock
    with IOValues
    with OptionValues
    with RouteHelpers
    with TestMatchers
    with Inspectors
    with RouteFixtures {

  private val projectUuid           = UUID.randomUUID()
  implicit private val uuidF: UUIDF = UUIDF.fixed(projectUuid)

  private val orgUuid = UUID.randomUUID()

  private val caller = Caller(alice, Set(alice, Anonymous, Authenticated(realm), Group("group", realm)))

  private val identities = IdentitiesDummy(Map(AuthToken("alice") -> caller))

  private val asAlice = addCredentials(OAuth2BearerToken("alice"))

  // Creating the org instance and injecting some data in it
  private val orgs = {
    implicit val subject: Identity.Subject = caller.subject
    for {
      o <- OrganizationsDummy()(uuidF = UUIDF.fixed(orgUuid))
      _ <- o.create(Label.unsafe("org1"), None)
      _ <- o.create(Label.unsafe("org2"), None)
      _ <- o.deprecate(Label.unsafe("org2"), 1L)

    } yield o
  }.accepted

  private val routes = Route.seal(ProjectsRoutes(identities, ProjectsDummy(orgs).accepted))

  val desc  = "Project description"
  val base  = "https://localhost/base/"
  val vocab = "https://localhost/voc/"

  val payload = jsonContentOf("/projects/create.json", "description" -> desc, "base" -> base, "vocab" -> vocab)

  val payloadUpdated =
    jsonContentOf("/projects/create.json", "description" -> "New description", "base" -> base, "vocab" -> vocab)

  val anotherPayload = jsonContentOf("/projects/create.json", "description" -> desc)

  "A project route" should {

    "create a project" in {
      Put("/v1/projects/org1/proj", payload.toEntity) ~> routes ~> check {
        status shouldEqual StatusCodes.Created
        response.asJson should equalIgnoreArrayOrder(
          jsonContentOf("/projects/meta.json", "org" -> "org1", "proj" -> "proj", "rev" -> 1, "deprecated" -> false)
        )
      }
    }

    "create a project with an authenticated user" in {
      Put("/v1/projects/org1/proj2", anotherPayload.toEntity) ~> asAlice ~> routes ~> check {
        status shouldEqual StatusCodes.Created
        response.asJson should equalIgnoreArrayOrder(
          jsonContentOf(
            "/projects/meta.json",
            "org"        -> "org1",
            "proj"       -> "proj2",
            "rev"        -> 1,
            "deprecated" -> false,
            "realm"      -> alice.realm,
            "user"       -> alice.subject
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

    "update a project" in {
      Put("/v1/projects/org1/proj?rev=1", payloadUpdated.toEntity) ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson should equalIgnoreArrayOrder(
          jsonContentOf("/projects/meta.json", "org" -> "org1", "proj" -> "proj", "rev" -> 2, "deprecated" -> false)
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

    "deprecate a project" in {
      Delete("/v1/projects/org1/proj?rev=2") ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson should equalIgnoreArrayOrder(
          jsonContentOf("/projects/meta.json", "org" -> "org1", "proj" -> "proj", "rev" -> 3, "deprecated" -> true)
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
      "org"         -> "org1",
      "proj"        -> "proj",
      "orgUuid"     -> orgUuid,
      "uuid"        -> projectUuid,
      "rev"         -> 2L,
      "deprecated"  -> false,
      "description" -> "New description",
      "base"        -> base,
      "vocab"       -> vocab
    )

    val fetchProjRev3 = jsonContentOf(
      "/projects/fetch.json",
      "org"         -> "org1",
      "proj"        -> "proj",
      "orgUuid"     -> orgUuid,
      "uuid"        -> projectUuid,
      "rev"         -> 3L,
      "deprecated"  -> true,
      "description" -> "New description",
      "base"        -> base,
      "vocab"       -> vocab
    )

    val fetchProj2 = jsonContentOf(
      "/projects/fetch.json",
      "org"         -> "org1",
      "proj"        -> "proj2",
      "orgUuid"     -> orgUuid,
      "uuid"        -> projectUuid,
      "rev"         -> 1L,
      "deprecated"  -> false,
      "description" -> "Project description",
      "base"        -> "http://localhost/v1/resources/org1/proj2/_/",
      "vocab"       -> "http://localhost/v1/vocabs/org1/proj2/",
      "user"        -> alice.subject,
      "realm"       -> alice.realm
    )

    "fetch a project" in {
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

    "fetch a project by uuid if orgUuid doesn't match" in {
      val unknown = UUID.randomUUID()
      Get(s"/v1/projects/$unknown/$projectUuid") ~> routes ~> check {
        status shouldEqual StatusCodes.NotFound
        response.asJson shouldEqual jsonContentOf(
          "/projects/errors/orguuid-no-match.json",
          "orgUuid"  -> unknown,
          "projUuid" -> projectUuid
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
        response.asJson shouldEqual jsonContentOf("/errors/resource-not-found.json")
      }
    }

    def expectedResults(results: Json*): Json =
      Json.obj(
        "@context" -> Json.arr(
          Json.fromString("https://bluebrain.github.io/nexus/contexts/resource.json"),
          Json.fromString("https://bluebrain.github.io/nexus/contexts/projects.json"),
          Json.fromString("https://bluebrain.github.io/nexus/contexts/search.json")
        ),
        "_total"   -> Json.fromInt(results.size),
        "_results" -> Json.arr(results: _*)
      )

    "list all projects" in {
      Get("/v1/projects") ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson should equalIgnoreArrayOrder(
          expectedResults(
            fetchProjRev3.removeKeys("@context"),
            fetchProj2.removeKeys("@context")
          )
        )
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
      Get(s"/v1/projects?updatedBy=${UrlUtils.encode(alice.id.toString)}") ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson should equalIgnoreArrayOrder(
          expectedResults(
            fetchProj2.removeKeys("@context")
          )
        )
      }
    }

    "get the events stream with an offset" in {
      Get("/v1/projects/events") ~> Accept(`*/*`) ~> `Last-Event-ID`("1") ~> routes ~> check {
        mediaType shouldBe `text/event-stream`
        response.asString shouldEqual contentOf(
          "/projects/eventstream-1-4.txt",
          "projectUuid" -> projectUuid,
          "orgUuid"     -> orgUuid
        )
      }
    }

  }
}
