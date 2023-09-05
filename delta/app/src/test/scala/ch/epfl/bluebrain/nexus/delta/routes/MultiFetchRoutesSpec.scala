package ch.epfl.bluebrain.nexus.delta.routes

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.OAuth2BearerToken
import akka.http.scaladsl.server.Route
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclSimpleCheck
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ResourceGen
import ch.epfl.bluebrain.nexus.delta.sdk.identities.IdentitiesDummy
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceRepresentation
import ch.epfl.bluebrain.nexus.delta.sdk.multifetch.MultiFetch
import ch.epfl.bluebrain.nexus.delta.sdk.multifetch.model.MultiFetchRequest
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions
import ch.epfl.bluebrain.nexus.delta.sdk.utils.BaseRouteSpec
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Anonymous, Authenticated, Group}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ResourceRef.Latest
import monix.bio.UIO

class MultiFetchRoutesSpec extends BaseRouteSpec {

  implicit private val caller: Caller =
    Caller(alice, Set(alice, Anonymous, Authenticated(realm), Group("group", realm)))

  private val asAlice = addCredentials(OAuth2BearerToken("alice"))

  private val identities = IdentitiesDummy(caller)

  private val project1 = ProjectRef.unsafe("org", "proj1")
  private val project2 = ProjectRef.unsafe("org", "proj2")

  private val permissions = Set(Permissions.resources.read)
  private val aclCheck    = AclSimpleCheck((alice, project1, permissions)).runSyncUnsafe()

  private val successId      = nxv + "success"
  private val successContent =
    ResourceGen.jsonLdContent(successId, project1, jsonContentOf("resources/resource.json", "id" -> successId))

  private val notFoundId     = nxv + "not-found"
  private val unauthorizedId = nxv + "unauthorized"

  private def fetchResource =
    (input: MultiFetchRequest.Input) => {
      input match {
        case MultiFetchRequest.Input(Latest(`successId`), `project1`) =>
          UIO.some(successContent)
        case _                                                        => UIO.none
      }
    }

  private val multiFetch = MultiFetch(
    aclCheck,
    fetchResource
  )

  private val routes = Route.seal(
    new MultiFetchRoutes(identities, aclCheck, multiFetch).routes
  )

  "The Multi fetch route" should {

    val endpoint = "/v1/multi-fetch/resources"

    def request(format: ResourceRepresentation) =
      json"""
        {
          "format": "$format",
          "resources": [
            { "id": "$successId", "project": "$project1" },
            { "id": "$notFoundId", "project": "$project1" },
            { "id": "$unauthorizedId", "project": "$project2" }
          ]
        }"""

    "return unauthorised results for a user with no access" in {
      val entity = request(ResourceRepresentation.CompactedJsonLd).toEntity
      Get(endpoint, entity) ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson shouldEqual jsonContentOf("multi-fetch/all-unauthorized.json")
      }
    }

    "return expected results as compacted json-ld for a user with limited access" in {
      val entity = request(ResourceRepresentation.CompactedJsonLd).toEntity
      Get(endpoint, entity) ~> asAlice ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson shouldEqual jsonContentOf("multi-fetch/compacted-response.json")
      }
    }

    "return expected results as annotated source for a user with limited access" in {
      val entity = request(ResourceRepresentation.AnnotatedSourceJson).toEntity
      Get(endpoint, entity) ~> asAlice ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson shouldEqual jsonContentOf("multi-fetch/annotated-source-response.json")
      }
    }

    "return expected results as original payloads for a user with limited access" in {
      val entity = request(ResourceRepresentation.SourceJson).toEntity
      Get(endpoint, entity) ~> asAlice ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson shouldEqual jsonContentOf("multi-fetch/source-response.json")
      }
    }
  }
}
