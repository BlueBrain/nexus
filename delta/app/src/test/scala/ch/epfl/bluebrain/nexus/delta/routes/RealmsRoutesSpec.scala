package ch.epfl.bluebrain.nexus.delta.routes

import akka.http.scaladsl.model.headers.OAuth2BearerToken
import akka.http.scaladsl.model.{StatusCodes, Uri}
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.testkit.ScalatestRouteTest
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
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

  implicit private val ordering: JsonKeyOrdering = JsonKeyOrdering.alphabetical
  implicit private val baseUri: BaseUri          = BaseUri("http://localhost", Label.unsafe("v1"))
  implicit private val paginationConfig          = PaginationConfig(5, 10, 5)
  implicit private val s: Scheduler              = Scheduler.global

  val (github, gitlab)         = (Label.unsafe("github"), Label.unsafe("gitlab"))
  val (githubName, gitlabName) = (Name.unsafe("github-name"), Name.unsafe("gitlab-name"))

  val githubLogo: Uri = "https://localhost/ghlogo"

  val (githubOpenId, githubWk) = WellKnownGen.create(github.value)
  val (gitlabOpenId, gitlabWk) = WellKnownGen.create(gitlab.value)

  private val realms = RealmsDummy(
    ioFromMap(
      Map(githubOpenId -> githubWk, gitlabOpenId -> gitlabWk),
      (uri: Uri) => UnsuccessfulOpenIdConfigResponse(uri)
    )
  ).accepted

  private val realm  = Label.unsafe("wonderland")
  private val alice  = User("alice", realm)
  private val caller = Caller(alice, Set(alice, Anonymous, Authenticated(realm), Group("group", realm)))

  private val identities = IdentitiesDummy(
    Map(
      AuthToken("alice") -> caller
    )
  )

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

    "list realms" in {
      Get("/v1/realms") ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson should equalIgnoreArrayOrder(
          jsonContentOf(
            "/realms/list-realms.json",
            "github" -> githubUpdated.removeKeys("@context"),
            "gitlab" -> gitlabCreated.removeKeys("@context")
          )
        )
      }
    }

    "deprecate a realm" in {
      Delete("/v1/realms/gitlab?rev=1") ~> addCredentials(OAuth2BearerToken("alice")) ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson should equalIgnoreArrayOrder(gitlabDeprecated)
      }
    }
  }
}
