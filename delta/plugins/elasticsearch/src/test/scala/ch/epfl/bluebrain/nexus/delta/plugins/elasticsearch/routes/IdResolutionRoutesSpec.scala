package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.routes

import akka.http.scaladsl.model.headers.Location
import akka.http.scaladsl.model.{HttpResponse, StatusCodes}
import akka.http.scaladsl.server.Route
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UrlUtils
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.IdResolution
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.IdResolution.ResolutionResult.SingleResult
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.fusion.FusionConfig
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ResourceGen
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ResourceRef
import io.circe.Decoder

class IdResolutionRoutesSpec extends ElasticSearchViewsRoutesFixtures {

  private val encodedIri = UrlUtils.encode("https://bbp.epfl.ch/data/resource")

  private val successId      = nxv + "success"
  private val jsonResource   = jsonContentOf("resources/resource.json", "id" -> successId)
  private val successContent = ResourceGen.jsonLdContent(successId, projectRef, jsonResource)

  implicit private val f: FusionConfig = fusionConfig

  private val idResolution = new IdResolution {

    override def apply(iri: Iri)(implicit caller: Caller): IO[IdResolution.ResolutionResult] =
      IO.pure(SingleResult(ResourceRef.Latest(successId), projectRef, successContent))
  }
  private val route =
    Route.seal(new IdResolutionRoutes(identities, aclCheck, idResolution).routes)

  "The IdResolution route" should {

    "return a resolved resource with project metadata" in {
      Get(s"/resolve/$encodedIri") ~> route ~> check {
        response.status shouldEqual StatusCodes.OK
        response.topLevelField[Iri]("@id") shouldEqual successId
        response.topLevelField[String]("_project") shouldEqual projectRef.toString
      }
    }

    "redirect the proxy call to the resolve endpoint" in {
      val segment             = s"neurosciencegraph/data/$uuid"
      val fullId              = s"https://bbp.epfl.ch/$segment"
      val expectedRedirection = s"$baseUri/resolve/${UrlUtils.encode(fullId)}".replace("%3A", ":")

      Get(s"/resolve-proxy-pass/$segment") ~> route ~> check {
        response.status shouldEqual StatusCodes.SeeOther
        response.locationHeader shouldEqual expectedRedirection
      }
    }

  }

  implicit class HeaderOps(response: HttpResponse) {
    def locationHeader: String = response.header[Location].value.uri.toString()

    def topLevelField[A: Decoder](field: String): A =
      response.asJson.hcursor.downField(field).as[A].toOption.get
  }

}
