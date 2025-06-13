package ai.senscience.nexus.delta.routes

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Route
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclSimpleCheck
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ResourceGen
import ch.epfl.bluebrain.nexus.delta.sdk.identities.IdentitiesDummy
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceRepresentation
import ch.epfl.bluebrain.nexus.delta.sdk.multifetch.MultiFetch
import ch.epfl.bluebrain.nexus.delta.sdk.multifetch.model.MultiFetchRequest
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions
import ch.epfl.bluebrain.nexus.delta.sdk.utils.BaseRouteSpec
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ResourceRef.Latest
import io.circe.Json

class MultiFetchRoutesSpec extends BaseRouteSpec {

  private val identities = IdentitiesDummy.fromUsers(alice)

  private val project1 = ProjectRef.unsafe("org", "proj1")
  private val project2 = ProjectRef.unsafe("org", "proj2")

  private val permissions = Set(Permissions.resources.read)
  private val aclCheck    = AclSimpleCheck((alice, project1, permissions)).accepted

  private val successId      = nxv + "success"
  private val successContent =
    ResourceGen.jsonLdContent(successId, project1, jsonContentOf("resources/resource.json", "id" -> successId))

  private val notFoundId     = nxv + "not-found"
  private val unauthorizedId = nxv + "unauthorized"

  private def fetchResource =
    (input: MultiFetchRequest.Input) => {
      input match {
        case MultiFetchRequest.Input(Latest(`successId`), `project1`) =>
          IO.pure(Some(successContent))
        case _                                                        => IO.none
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

    def multiFetchQuery[T](payload: Json)(checks: => T) =
      List(Get, Post).foreach { method =>
        method(endpoint, payload.toEntity) ~> as(alice) ~> routes ~> check { checks }
      }

    "return unauthorised results for a user with no access" in {
      val entity = request(ResourceRepresentation.CompactedJsonLd).toEntity
      List(Get, Post).foreach { method =>
        method(endpoint, entity) ~> routes ~> check {
          status shouldEqual StatusCodes.OK
          response.asJson shouldEqual jsonContentOf("multi-fetch/all-unauthorized.json")
        }
      }
    }

    "return expected results as compacted json-ld for a user with limited access" in {
      val payload = request(ResourceRepresentation.CompactedJsonLd)
      multiFetchQuery(payload) {
        status shouldEqual StatusCodes.OK
        response.asJson shouldEqual jsonContentOf("multi-fetch/compacted-response.json")
      }
    }

    "return expected results as annotated source for a user with limited access" in {
      val payload = request(ResourceRepresentation.AnnotatedSourceJson)
      multiFetchQuery(payload) {
        status shouldEqual StatusCodes.OK
        response.asJson shouldEqual jsonContentOf("multi-fetch/annotated-source-response.json")
      }
    }

    "return expected results as original payloads for a user with limited access" in {
      val payload = request(ResourceRepresentation.SourceJson)
      multiFetchQuery(payload) {
        status shouldEqual StatusCodes.OK
        response.asJson shouldEqual jsonContentOf("multi-fetch/source-response.json")
      }
    }
  }
}
