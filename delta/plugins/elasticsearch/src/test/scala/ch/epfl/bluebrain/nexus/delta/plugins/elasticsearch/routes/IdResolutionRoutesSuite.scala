package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.routes

import akka.http.scaladsl.model.headers.Location
import akka.http.scaladsl.model.{HttpHeader, HttpResponse, StatusCodes}
import akka.http.scaladsl.server.Route
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UrlUtils
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.IdResolution
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.query.{DefaultSearchRequest, DefaultViewsQuery, ElasticSearchQueryError}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.routes.DummyDefaultViewsQuery.{aggregationResponse, Aggregation, Result}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.fusion.FusionConfig
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ResourceGen
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.{AggregationResult, SearchResults}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{ProjectRef, ResourceRef}
import monix.bio.{IO, UIO}

class IdResolutionRoutesSuite extends ElasticSearchViewsRoutesFixtures {

  private val encodedIri = UrlUtils.encode("https://bbp.epfl.ch/data/resource")

  private val successId      = nxv + "success"
  private val jsonResource   = jsonContentOf("resources/resource.json", "id" -> successId)
  private val successContent =
    ResourceGen.jsonLdContent(successId, projectRef, jsonResource)
  private def fetchResource  =
    (_: ResourceRef, _: ProjectRef) => UIO.some(successContent)

  implicit private val f: FusionConfig = fusionConfig

  private val listResponse = jobj"""{"_project": "https://bbp.epfl.ch/nexus/v1/projects/$projectRef"}"""

  private lazy val dummyDefaultViewsQuery =
    new DefaultViewsQuery[Result, Aggregation] {
      override def list(searchRequest: DefaultSearchRequest)(implicit
          caller: Caller
      ): IO[ElasticSearchQueryError, Result] =
        IO.pure(SearchResults(1, List(listResponse)))

      override def aggregate(searchRequest: DefaultSearchRequest)(implicit
          caller: Caller
      ): IO[ElasticSearchQueryError, Aggregation] =
        IO.pure(AggregationResult(1, aggregationResponse))
    }

  private val idResolution = new IdResolution(dummyDefaultViewsQuery, fetchResource)
  private val route        = Route.seal(new IdResolutionRoutes(identities, aclCheck, idResolution, baseUri).routes)

  "The IdResolution route" should {

    "return a resolved resource" in {
      Get(s"/resolve/$encodedIri") ~> route ~> check {
        response.status shouldEqual StatusCodes.OK
        response.asJson shouldEqual jsonResource
      }
    }

    "redirect the proxy call to the resolve endpoint" in {
      val fullId              = s"https://bbp.epfl.ch/neurosciencegraph/data/$uuid"
      val expectedRedirection = s"$baseUri/${UrlUtils.encode(fullId)}"

      Get(s"/resolve-proxy-pass/$uuid") ~> route ~> check {
        response.status shouldEqual StatusCodes.SeeOther
        response.locationHeader shouldEqual expectedRedirection
      }
    }

  }

  implicit class HeaderOps(response: HttpResponse) {
    def locationHeader: String = response.header[Location].value.uri.toString()
  }

}
