package ai.senscience.nexus.delta.routes

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Route
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UrlUtils.encodeUriQuery
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclSimpleCheck
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.generators.WellKnownGen
import ch.epfl.bluebrain.nexus.delta.sdk.identities.IdentitiesDummy
import ch.epfl.bluebrain.nexus.delta.sdk.implicits.*
import ch.epfl.bluebrain.nexus.delta.sdk.model.Name
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions.realms as realmsPermissions
import ch.epfl.bluebrain.nexus.delta.sdk.realms.model.RealmRejection.UnsuccessfulOpenIdConfigResponse
import ch.epfl.bluebrain.nexus.delta.sdk.realms.{RealmsConfig, RealmsImpl, RealmsProvisioningConfig}
import ch.epfl.bluebrain.nexus.delta.sdk.utils.BaseRouteSpec
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Anonymous, Subject}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.testkit.ce.IOFromMap
import io.circe.Json
import org.http4s.Uri

class RealmsRoutesSpec extends BaseRouteSpec with IOFromMap {

  val (github, gitlab)         = (Label.unsafe("github"), Label.unsafe("gitlab"))
  val (githubName, gitlabName) = (Name.unsafe("github-name"), Name.unsafe("gitlab-name"))

  val githubLogo: Uri = Uri.unsafeFromString("https://localhost/ghlogo")

  private val provisioning = RealmsProvisioningConfig(enabled = false, Map.empty)
  private val config       = RealmsConfig(eventLogConfig, pagination, provisioning)

  val (githubOpenId, githubWk) = WellKnownGen.create(github.value)
  val (gitlabOpenId, gitlabWk) = WellKnownGen.create(gitlab.value)

  private lazy val realms = RealmsImpl(
    config,
    ioFromMap(
      Map(githubOpenId -> githubWk, gitlabOpenId -> gitlabWk),
      (uri: Uri) => UnsuccessfulOpenIdConfigResponse(uri)
    ),
    xas,
    clock
  )

  private val identities = IdentitiesDummy.fromUsers(alice)
  private val aclCheck   = AclSimpleCheck().accepted

  private lazy val routes = Route.seal(RealmsRoutes(identities, realms, aclCheck))

  private val githubCreatedMeta = realmMetadata(github)
  private val githubUpdatedMeta = realmMetadata(github, rev = 2)
  private val gitlabCreatedMeta = realmMetadata(gitlab, createdBy = alice, updatedBy = alice)

  private val gitlabDeprecatedMeta =
    realmMetadata(gitlab, rev = 2, deprecated = true, createdBy = alice, updatedBy = alice)

  private val githubCreated = jsonContentOf(
    "realms/realm-resource.json",
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
    "realms/realm-resource.json",
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
      aclCheck.replace(AclAddress.Root, Anonymous -> Set(realmsPermissions.read)).accepted
      val input = json"""{"name": "${githubName.value}", "openIdConfig": "$githubOpenId", "logo": "$githubLogo"}"""

      Put("/v1/realms/github", input.toEntity) ~> routes ~> check {
        response.shouldBeForbidden
      }
    }

    "create a new realm" in {
      aclCheck.append(AclAddress.Root, Anonymous -> Set(realmsPermissions.write)).accepted
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
      Put("/v1/realms/gitlab", input.toEntity) ~> as(alice) ~> routes ~> check {
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
      aclCheck.subtract(AclAddress.Root, Anonymous -> Set(realmsPermissions.read)).accepted
      Get("/v1/realms/github") ~> routes ~> check {
        response.shouldBeForbidden
      }
    }

    "fail to list realms  without realms/read permission" in {
      Get("/v1/realms") ~> routes ~> check {
        response.shouldBeForbidden
      }
    }

    "fetch a realm by id" in {
      aclCheck.append(AclAddress.Root, Anonymous -> Set(realmsPermissions.read)).accepted
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
        response.asJson shouldEqual jsonContentOf("errors/revision-not-found.json", "provided" -> 4, "current" -> 2)
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
        "_results" -> Json.arr(results*)
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
      Get(s"/v1/realms?createdBy=${encodeUriQuery(alice.asIri.toString)}") ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson should equalIgnoreArrayOrder(
          expectedResults(
            gitlabCreated.removeKeys("@context")
          )
        )
      }
    }

    "deprecate a realm" in {
      Delete("/v1/realms/gitlab?rev=1") ~> as(alice) ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson should equalIgnoreArrayOrder(gitlabDeprecatedMeta)
      }
    }
  }

  def realmMetadata(
      label: Label,
      rev: Int = 1,
      deprecated: Boolean = false,
      createdBy: Subject = Anonymous,
      updatedBy: Subject = Anonymous
  ): Json        =
    jsonContentOf(
      "realms/realm-route-metadata-response.json",
      "rev"        -> rev,
      "deprecated" -> deprecated,
      "createdBy"  -> createdBy.asIri,
      "updatedBy"  -> updatedBy.asIri,
      "label"      -> label
    )
}
