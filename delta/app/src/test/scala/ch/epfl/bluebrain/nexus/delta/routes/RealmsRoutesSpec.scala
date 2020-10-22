package ch.epfl.bluebrain.nexus.delta.routes

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

import akka.http.scaladsl.model.MediaRanges.`*/*`
import akka.http.scaladsl.model.MediaTypes.`text/event-stream`
import akka.http.scaladsl.model.headers.{`Last-Event-ID`, Accept, OAuth2BearerToken}
import akka.http.scaladsl.model.{StatusCodes, Uri}
import akka.http.scaladsl.server.{RejectionHandler, Route}
import akka.http.scaladsl.testkit.ScalatestRouteTest
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.routes.marshalling.RdfRejectionHandler
import ch.epfl.bluebrain.nexus.delta.sdk.generators.WellKnownGen
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.{Anonymous, Authenticated, Group, User}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.{AuthToken, Caller}
import ch.epfl.bluebrain.nexus.delta.sdk.model.realms.RealmRejection.UnsuccessfulOpenIdConfigResponse
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.PaginationConfig
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Label, Name}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.{IdentitiesDummy, RealmsDummy, RemoteContextResolutionDummy}
import ch.epfl.bluebrain.nexus.delta.utils.RouteHelpers
import ch.epfl.bluebrain.nexus.testkit._
import io.circe.Json
import monix.execution.Scheduler
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.{Inspectors, OptionValues}

class RealmsRoutesSpec
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
    with TestHelpers {

  implicit private val rcr: RemoteContextResolutionDummy =
    RemoteContextResolutionDummy(
      contexts.resource -> jsonContentOf("contexts/resource.json"),
      contexts.error    -> jsonContentOf("contexts/error.json"),
      contexts.realms   -> jsonContentOf("contexts/realms.json")
    )

  implicit private val ordering: JsonKeyOrdering          = JsonKeyOrdering.alphabetical
  implicit private val baseUri: BaseUri                   = BaseUri("http://localhost", Label.unsafe("v1"))
  implicit private val paginationConfig: PaginationConfig = PaginationConfig(5, 10, 5)
  implicit private val s: Scheduler                       = Scheduler.global

  val (github, gitlab)         = (Label.unsafe("github"), Label.unsafe("gitlab"))
  val (githubName, gitlabName) = (Name.unsafe("github-name"), Name.unsafe("gitlab-name"))

  val githubLogo: Uri = "https://localhost/ghlogo"

  val (githubOpenId, githubWk) = WellKnownGen.create(github.value)
  val (gitlabOpenId, gitlabWk) = WellKnownGen.create(gitlab.value)

  private val realms = RealmsDummy(
    ioFromMap(
      Map(githubOpenId -> githubWk, gitlabOpenId -> gitlabWk),
      (uri: Uri) => UnsuccessfulOpenIdConfigResponse(uri)
    ),
    2L
  ).accepted

  private val realm                                       = Label.unsafe("wonderland")
  private val alice                                       = User("alice", realm)
  private val caller                                      = Caller(alice, Set(alice, Anonymous, Authenticated(realm), Group("group", realm)))
  implicit private val rejectionHandler: RejectionHandler = RdfRejectionHandler.apply

  private val identities = IdentitiesDummy(Map(AuthToken("alice") -> caller))

  private val routes = Route.seal(RealmsRoutes(identities, realms).routes)

  private val githubCreated = jsonContentOf(
    "/realms/realm-resource.json",
    Map(
      "label"                 -> github.value,
      "name"                  -> githubName.value,
      "openIdConfig"          -> githubOpenId,
      "logo"                  -> githubLogo,
      "issuer"                -> github.value,
      "authorizationEndpoint" -> githubWk.authorizationEndpoint,
      "tokenEndpoint"         -> githubWk.tokenEndpoint,
      "userInfoEndpoint"      -> githubWk.userInfoEndpoint,
      "revocationEndpoint"    -> githubWk.revocationEndpoint.value,
      "endSessionEndpoint"    -> githubWk.endSessionEndpoint.value,
      "createdBy"             -> Anonymous.id,
      "updatedBy"             -> Anonymous.id,
      "deprecated"            -> false,
      "rev"                   -> 1L
    )
  )

  private val githubUpdated = githubCreated.deepMerge(
    Json.obj(
      "name" -> Json.fromString("updated"),
      "_rev" -> Json.fromLong(2L)
    )
  )

  private val gitlabCreated = jsonContentOf(
    "/realms/realm-resource.json",
    Map(
      "label"                 -> gitlab.value,
      "name"                  -> gitlabName.value,
      "openIdConfig"          -> gitlabOpenId,
      "logo"                  -> githubLogo,
      "issuer"                -> gitlab.value,
      "authorizationEndpoint" -> gitlabWk.authorizationEndpoint,
      "tokenEndpoint"         -> gitlabWk.tokenEndpoint,
      "userInfoEndpoint"      -> gitlabWk.userInfoEndpoint,
      "revocationEndpoint"    -> gitlabWk.revocationEndpoint.value,
      "endSessionEndpoint"    -> gitlabWk.endSessionEndpoint.value,
      "createdBy"             -> alice.id,
      "updatedBy"             -> alice.id,
      "deprecated"            -> false,
      "rev"                   -> 1L
    )
  )

  private val gitlabDeprecated = gitlabCreated.deepMerge(
    Json.obj(
      "_deprecated" -> Json.fromBoolean(true),
      "_rev"        -> Json.fromLong(2L)
    )
  )

  "A RealmsRoute" should {
    "create a new realm" in {
      val input = Json.obj(
        "name"         -> Json.fromString(githubName.value),
        "openIdConfig" -> Json.fromString(githubOpenId.toString()),
        "logo"         -> Json.fromString(githubLogo.toString())
      )

      Put("/v1/realms/github", input.toEntity) ~> routes ~> check {
        status shouldEqual StatusCodes.Created
        response.asJson shouldEqual githubCreated
      }
    }

    "fail when creating another realm with the same openIdConfig" in {
      val input = Json.obj(
        "name"         -> Json.fromString("duplicate"),
        "openIdConfig" -> Json.fromString(githubOpenId.toString())
      )

      val expected = Json.obj(
        "@context" -> Json.fromString("https://bluebrain.github.io/nexus/contexts/error.json"),
        "@type"    -> Json.fromString("RealmOpenIdConfigAlreadyExists"),
        "reason"   -> Json.fromString(
          "Realm 'duplicate' with openIdConfig 'https://localhost/auth/github/protocol/openid-connect/' already exists."
        )
      )

      Put("/v1/realms/duplicate", input.toEntity) ~> routes ~> check {
        status shouldEqual StatusCodes.BadRequest
        response.asJson shouldEqual expected
      }
    }

    "create another realm with an authenticated user" in {
      val input = Json.obj(
        "name"         -> Json.fromString(gitlabName.value),
        "openIdConfig" -> Json.fromString(gitlabOpenId.toString()),
        "logo"         -> Json.fromString(githubLogo.toString())
      )

      Put("/v1/realms/gitlab", input.toEntity) ~> addCredentials(OAuth2BearerToken("alice")) ~> routes ~> check {
        status shouldEqual StatusCodes.Created
        response.asJson shouldEqual gitlabCreated
      }
    }

    "update an existing realm" in {
      val input = Json.obj(
        "name"         -> Json.fromString("updated"),
        "openIdConfig" -> Json.fromString(githubOpenId.toString()),
        "logo"         -> Json.fromString(githubLogo.toString())
      )

      Put("/v1/realms/github?rev=1", input.toEntity) ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson shouldEqual githubUpdated
      }
    }

    "fetch a realm by id" in {
      Get("/v1/realms/github") ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson shouldEqual githubUpdated
      }
    }

    "fetch a realm by id and rev" in {
      Get("/v1/realms/github?rev=1") ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson shouldEqual githubCreated
      }
    }

    "fail fetching a realm by id and rev when rev is invalid" in {
      Get("/v1/realms/github?rev=4") ~> routes ~> check {
        status shouldEqual StatusCodes.NotFound
        response.asJson shouldEqual jsonContentOf("/realms/revision-not-found.json", "provided" -> 4, "current" -> 2)
      }
    }

    def expectedResults(results: Json*): Json =
      Json.obj(
        "@context" -> Json.arr(
          Json.fromString("https://bluebrain.github.io/nexus/contexts/resource.json"),
          Json.fromString("https://bluebrain.github.io/nexus/contexts/realms.json"),
          Json.fromString("https://bluebrain.github.io/nexus/contexts/search.json")
        ),
        "_total"   -> Json.fromInt(results.size),
        "_results" -> Json.arr(results: _*)
      )

    "list realms" in {
      Get("/v1/realms") ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson should equalIgnoreArrayOrder(
          expectedResults(
            githubUpdated.removeKeys("@context"),
            gitlabCreated.removeKeys("@context")
          )
        )
      }
    }

    "list realms with revision 2" in {
      Get("/v1/realms?rev=2") ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson should equalIgnoreArrayOrder(
          expectedResults(
            githubUpdated.removeKeys("@context")
          )
        )
      }
    }

    "list realms created by alice" in {
      Get(s"/v1/realms?createdBy=${URLEncoder.encode(alice.id.toString, StandardCharsets.UTF_8)}") ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson should equalIgnoreArrayOrder(
          expectedResults(
            gitlabCreated.removeKeys("@context")
          )
        )
      }
    }

    "failed list realms created by a group" in {
      val group = Group("mygroup", Label.unsafe("myrealm"))
      Get(s"/v1/realms?createdBy=${URLEncoder.encode(group.id.toString, StandardCharsets.UTF_8)}") ~> routes ~> check {
        status shouldEqual StatusCodes.BadRequest
        response.asJson shouldEqual jsonContentOf("realms/malformed-query-param.json")
      }
    }

    "deprecate a realm" in {
      Delete("/v1/realms/gitlab?rev=1") ~> addCredentials(OAuth2BearerToken("alice")) ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson should equalIgnoreArrayOrder(gitlabDeprecated)
      }
    }

    "get the events stream with an offset" in {
      Get("/v1/realms/events") ~> Accept(`*/*`) ~> `Last-Event-ID`("2") ~> routes ~> check {
        mediaType shouldBe `text/event-stream`
        response.asString shouldEqual contentOf("/realms/eventstream-2-4.txt")
      }
    }
  }
}
