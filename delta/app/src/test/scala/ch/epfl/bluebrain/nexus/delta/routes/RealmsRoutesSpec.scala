package ch.epfl.bluebrain.nexus.delta.routes

import akka.http.scaladsl.model.MediaRanges.`*/*`
import akka.http.scaladsl.model.MediaTypes.`text/event-stream`
import akka.http.scaladsl.model.headers.{`Last-Event-ID`, Accept, OAuth2BearerToken}
import akka.http.scaladsl.model.{StatusCodes, Uri}
import akka.http.scaladsl.server.Route
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UrlUtils
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.sdk.generators.WellKnownGen
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.{Acl, AclAddress}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.{Anonymous, Authenticated, Group, Subject}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.{AuthToken, Caller, Identity}
import ch.epfl.bluebrain.nexus.delta.sdk.model.realms.RealmRejection.UnsuccessfulOpenIdConfigResponse
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Label, Name}
import ch.epfl.bluebrain.nexus.delta.sdk.Permissions.{events, realms => realmsPermissions}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.{AclsDummy, IdentitiesDummy, PermissionsDummy, RealmsDummy}
import ch.epfl.bluebrain.nexus.delta.sdk.utils.RouteHelpers
import ch.epfl.bluebrain.nexus.delta.utils.RouteFixtures
import ch.epfl.bluebrain.nexus.testkit._
import io.circe.Json
import org.scalatest.matchers.should.Matchers
import org.scalatest.{Inspectors, OptionValues}

class RealmsRoutesSpec
    extends RouteHelpers
    with Matchers
    with CirceLiteral
    with CirceEq
    with IOFixedClock
    with IOValues
    with OptionValues
    with TestMatchers
    with Inspectors
    with RouteFixtures {

  implicit private val subject: Subject = Identity.Anonymous

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

  private val caller = Caller(alice, Set(alice, Anonymous, Authenticated(realm), Group("group", realm)))

  private val identities = IdentitiesDummy(Map(AuthToken("alice") -> caller))
  private val acls       = AclsDummy(
    PermissionsDummy(Set(realmsPermissions.read, realmsPermissions.write, events.read))
  ).accepted

  private val routes = Route.seal(RealmsRoutes(identities, realms, acls))

  private val githubCreatedMeta = realmMetadata(github)
  private val githubUpdatedMeta = realmMetadata(github, rev = 2L)
  private val gitlabCreatedMeta = realmMetadata(gitlab, createdBy = alice, updatedBy = alice)

  private val gitlabDeprecatedMeta =
    realmMetadata(gitlab, rev = 2L, deprecated = true, createdBy = alice, updatedBy = alice)

  private val githubCreated = jsonContentOf(
    "/realms/realm-resource.json",
    "label"                 -> github.value,
    "name"                  -> githubName.value,
    "openIdConfig"          -> githubOpenId,
    "logo"                  -> githubLogo,
    "issuer"                -> github.value,
    "authorizationEndpoint" -> githubWk.authorizationEndpoint,
    "tokenEndpoint"         -> githubWk.tokenEndpoint,
    "userInfoEndpoint"      -> githubWk.userInfoEndpoint,
    "revocationEndpoint"    -> githubWk.revocationEndpoint.value,
    "endSessionEndpoint"    -> githubWk.endSessionEndpoint.value
  ) deepMerge githubCreatedMeta.removeKeys("@context")

  private val githubUpdated = githubCreated deepMerge
    json"""{"name": "updated"}""" deepMerge
    githubUpdatedMeta.removeKeys("@context")

  private val gitlabCreated = jsonContentOf(
    "/realms/realm-resource.json",
    "label"                 -> gitlab.value,
    "name"                  -> gitlabName.value,
    "openIdConfig"          -> gitlabOpenId,
    "logo"                  -> githubLogo,
    "issuer"                -> gitlab.value,
    "authorizationEndpoint" -> gitlabWk.authorizationEndpoint,
    "tokenEndpoint"         -> gitlabWk.tokenEndpoint,
    "userInfoEndpoint"      -> gitlabWk.userInfoEndpoint,
    "revocationEndpoint"    -> gitlabWk.revocationEndpoint.value,
    "endSessionEndpoint"    -> gitlabWk.endSessionEndpoint.value
  ) deepMerge gitlabCreatedMeta

  "A RealmsRoute" should {

    "fail to create a realm  without realms/write permission" in {
      acls.replace(Acl(AclAddress.Root, Anonymous -> Set(realmsPermissions.read)), 0L).accepted
      val input = json"""{"name": "${githubName.value}", "openIdConfig": "$githubOpenId", "logo": "$githubLogo"}"""

      Put("/v1/realms/github", input.toEntity) ~> routes ~> check {
        response.status shouldEqual StatusCodes.Forbidden
        response.asJson shouldEqual jsonContentOf("errors/authorization-failed.json")
      }
    }

    "create a new realm" in {
      acls.append(Acl(AclAddress.Root, Anonymous -> Set(realmsPermissions.write)), 1L).accepted
      val input = json"""{"name": "${githubName.value}", "openIdConfig": "$githubOpenId", "logo": "$githubLogo"}"""

      Put("/v1/realms/github", input.toEntity) ~> routes ~> check {
        status shouldEqual StatusCodes.Created
        response.asJson shouldEqual githubCreatedMeta
      }
    }

    "fail when creating another realm with the same openIdConfig" in {

      val input = json"""{"name": "duplicate", "openIdConfig": "$githubOpenId"}"""

      val expected =
        json"""{"@context": "${contexts.error}", "@type": "RealmOpenIdConfigAlreadyExists", "reason": "Realm 'duplicate' with openIdConfig 'https://localhost/auth/github/protocol/openid-connect/' already exists."}"""

      Put("/v1/realms/duplicate", input.toEntity) ~> routes ~> check {
        status shouldEqual StatusCodes.BadRequest
        response.asJson shouldEqual expected
      }
    }

    "create another realm with an authenticated user" in {

      val input = json"""{"name": "$gitlabName", "openIdConfig": "$gitlabOpenId", "logo": "$githubLogo"}"""
      Put("/v1/realms/gitlab", input.toEntity) ~> addCredentials(OAuth2BearerToken("alice")) ~> routes ~> check {
        status shouldEqual StatusCodes.Created
        response.asJson shouldEqual gitlabCreatedMeta
      }
    }

    "update an existing realm" in {

      val input = json"""{"name": "updated", "openIdConfig": "$githubOpenId", "logo": "$githubLogo"}"""

      Put("/v1/realms/github?rev=1", input.toEntity) ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson shouldEqual githubUpdatedMeta
      }
    }

    "fail to fetch a realm  without realms/read permission" in {
      acls.subtract(Acl(AclAddress.Root, Anonymous -> Set(realmsPermissions.read)), 2L).accepted
      Get("/v1/realms/github") ~> routes ~> check {
        response.status shouldEqual StatusCodes.Forbidden
        response.asJson shouldEqual jsonContentOf("errors/authorization-failed.json")
      }
    }

    "fail to list realms  without realms/read permission" in {
      Get("/v1/realms") ~> routes ~> check {
        response.status shouldEqual StatusCodes.Forbidden
        response.asJson shouldEqual jsonContentOf("errors/authorization-failed.json")
      }
    }

    "fetch a realm by id" in {
      acls.append(Acl(AclAddress.Root, Anonymous -> Set(realmsPermissions.read)), 3L).accepted
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
        response.asJson shouldEqual jsonContentOf("/errors/revision-not-found.json", "provided" -> 4, "current" -> 2)
      }
    }

    def expectedResults(results: Json*): Json =
      Json.obj(
        "@context" -> Json.arr(
          Json.fromString("https://bluebrain.github.io/nexus/contexts/metadata.json"),
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
      Get(s"/v1/realms?createdBy=${UrlUtils.encode(alice.id.toString)}") ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson should equalIgnoreArrayOrder(
          expectedResults(
            gitlabCreated.removeKeys("@context")
          )
        )
      }
    }

    "deprecate a realm" in {
      Delete("/v1/realms/gitlab?rev=1") ~> addCredentials(OAuth2BearerToken("alice")) ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson should equalIgnoreArrayOrder(gitlabDeprecatedMeta)
      }
    }

    "fail to get the events stream without events/read permission" in {
      Get("/v1/realms/events") ~> Accept(`*/*`) ~> `Last-Event-ID`("2") ~> routes ~> check {
        response.status shouldEqual StatusCodes.Forbidden
        response.asJson shouldEqual jsonContentOf("errors/authorization-failed.json")
      }
    }

    "get the events stream with an offset" in {
      acls.append(Acl(AclAddress.Root, Anonymous -> Set(events.read)), 4L).accepted
      Get("/v1/realms/events") ~> Accept(`*/*`) ~> `Last-Event-ID`("2") ~> routes ~> check {
        mediaType shouldBe `text/event-stream`
        response.asString.strip shouldEqual contentOf("/realms/eventstream-2-4.txt").strip
      }
    }
  }
}
