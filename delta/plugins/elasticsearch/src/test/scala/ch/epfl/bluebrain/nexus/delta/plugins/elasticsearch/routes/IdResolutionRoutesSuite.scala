package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.routes

import akka.http.scaladsl.model.StatusCodes
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.IdResolution
import ch.epfl.bluebrain.nexus.delta.sdk.fusion.FusionConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{ProjectRef, ResourceRef}
import monix.bio.UIO

class IdResolutionRoutesSuite extends ElasticSearchViewsRoutesFixtures {

//  private val org = Label.unsafe("org")
//  private val project1    = ProjectRef(org, Label.unsafe("proj"))
//  private val successId = nxv + "success"
//  private val successContent =
//    ResourceGen.jsonLdContent(successId, project1, jsonContentOf("resources/resource.json", "id" -> successId))

  private def fetchResource =
    (_: ResourceRef, _: ProjectRef) => UIO.none

  private lazy val defaultViewsQuery   = new DummyDefaultViewsQuery
  implicit private val f: FusionConfig = fusionConfig

  private val idResolution = new IdResolution(defaultViewsQuery, fetchResource)
  private val route        = new IdResolutionRoutes(identities, aclCheck, idResolution).routes

  "The IdResolution route" should {

    "fail without authorization" in {
      Get("/v1/resolve") ~> route ~> check {
        response.status shouldEqual StatusCodes.Forbidden
        response.asJson shouldEqual jsonContentOf("errors/authorization-failed.json")
      }
    }

    "return a resolved resource" in {}

    "redirect to fusion when the text header is present" in {}

  }

}
