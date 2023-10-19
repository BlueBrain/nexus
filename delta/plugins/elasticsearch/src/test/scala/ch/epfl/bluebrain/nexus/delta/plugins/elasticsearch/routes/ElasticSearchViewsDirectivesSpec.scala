package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.routes

import akka.http.scaladsl.model.MediaRanges.`*/*`
import akka.http.scaladsl.model.headers.Accept
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import ch.epfl.bluebrain.nexus.delta.kernel.search.TimeRange
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UrlUtils
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ResourcesSearchParams
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ResourcesSearchParams.Type.{ExcludedType, IncludedType}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.sdk.circe.CirceMarshalling
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.Sort.OrderType
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.{Sort, SortList}
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.{ApiMappings, ProjectContext}
import ch.epfl.bluebrain.nexus.delta.sdk.utils.RouteHelpers
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.User
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ResourceRef}
import ch.epfl.bluebrain.nexus.testkit.scalatest.TestMatchers
import ch.epfl.bluebrain.nexus.testkit.scalatest.bio.IOValues
import ch.epfl.bluebrain.nexus.testkit.{CirceLiteral, TestHelpers}
import io.circe.generic.extras.Configuration
import io.circe.Codec
import io.circe.syntax.EncoderOps
import org.scalatest.matchers.should.Matchers
import org.scalatest.{Inspectors, OptionValues}
import io.circe.generic.extras.semiauto.deriveConfiguredCodec

import java.time.Instant

class ElasticSearchViewsDirectivesSpec
    extends RouteHelpers
    with CirceMarshalling
    with Matchers
    with OptionValues
    with CirceLiteral
    with ElasticSearchViewsDirectives
    with IOValues
    with TestMatchers
    with TestHelpers
    with Inspectors {

  implicit private val baseUri: BaseUri = BaseUri("http://localhost", Label.unsafe("v1"))

  private val mappings                    = ApiMappings("alias" -> (nxv + "alias"), "nxv" -> nxv.base)
  private val base                        = iri"http://localhost/base/"
  private val vocab                       = iri"http://localhost/vocab/"
  implicit private val pc: ProjectContext = ProjectContext.unsafe(mappings, base, vocab)

  implicit val configuration: Configuration = Configuration.default.withDiscriminator(keywords.tpe)
  import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Database._

  implicit val timeRangeCodec: Codec[TimeRange] = deriveConfiguredCodec[TimeRange]

  implicit val orderTypeCodec: Codec[OrderType] = deriveConfiguredCodec[OrderType]
  implicit val sortCodec: Codec[Sort]           = deriveConfiguredCodec[Sort]
  implicit val sortListCodec: Codec[SortList]   = deriveConfiguredCodec[SortList]

  implicit val paramTypesCodec: Codec[ResourcesSearchParams.Type]                = deriveConfiguredCodec[ResourcesSearchParams.Type]
  implicit val paramTypeOperatorCodec: Codec[ResourcesSearchParams.TypeOperator] =
    deriveConfiguredCodec[ResourcesSearchParams.TypeOperator]
  implicit val paramsCodec: Codec[ResourcesSearchParams]                         = deriveConfiguredCodec[ResourcesSearchParams]

  private val route: Route =
    get {
      concat(
        (pathPrefix("sort") & sortList & pathEndOrSingleSlash) { list =>
          complete(list)
        },
        (pathPrefix("search") & projectRef & pathEndOrSingleSlash) { _ =>
          searchParameters(baseUri, pc).apply { params => complete(params.asJson) }
        }
      )
    }

  "A route" should {

    "return the sort parameters" in {
      Get("/sort?sort=+deprecated&sort=-@id&sort=_createdBy") ~> Accept(`*/*`) ~> route ~> check {
        val expected = SortList(List(Sort("deprecated"), Sort("-@id"), Sort("_createdBy")))
        response.as[SortList] shouldEqual expected
      }
    }

    "return the search parameters" in {
      val alicia   = User("alicia", Label.unsafe("myrealm"))
      val aliciaId = UrlUtils.encode(alicia.asIri.toString)
      val bob      = User("bob", Label.unsafe("myrealm"))
      val bobId    = UrlUtils.encode(bob.asIri.toString)

      val createdAt        = TimeRange.Before(Instant.EPOCH)
      val createdAtEncoded = UrlUtils.encode(s"*..${createdAt.value}")
      val updatedAt        = TimeRange.Between(Instant.EPOCH, Instant.EPOCH.plusSeconds(5L))
      val updatedAtEncoded = UrlUtils.encode(s"${updatedAt.start}..${updatedAt.end}")

      val query    = List(
        "locate"     -> "self",
        "id"         -> "myId",
        "deprecated" -> "false",
        "rev"        -> "2",
        "createdBy"  -> aliciaId,
        "createdAt"  -> createdAtEncoded,
        "updatedBy"  -> bobId,
        "updatedAt"  -> updatedAtEncoded,
        "rev"        -> "2",
        "type"       -> "A",
        "type"       -> "B",
        "type"       -> "-C",
        "schema"     -> "mySchema",
        "q"          -> "something"
      ).map { case (k, v) => s"$k=$v" }.mkString("&")

      val expected = ResourcesSearchParams(
        locate = Some(iri"${base}self"),
        id = Some(iri"${base}myId"),
        deprecated = Some(false),
        rev = Some(2),
        createdBy = Some(alicia),
        createdAt = createdAt,
        updatedBy = Some(bob),
        updatedAt = updatedAt,
        types = List(
          IncludedType(iri"${vocab}A"),
          IncludedType(iri"${vocab}B"),
          ExcludedType(iri"${vocab}C")
        ),
        schema = Some(ResourceRef.Latest(iri"${base}mySchema")),
        q = Some("something")
      )

      Get(s"/search/org/project?$query") ~> Accept(`*/*`) ~> route ~> check {
        response.as[ResourcesSearchParams] shouldEqual expected
      }
    }

    "return empty search parameters" in {
      Get("/search/org/project") ~> Accept(`*/*`) ~> route ~> check {
        response.as[ResourcesSearchParams] shouldEqual ResourcesSearchParams()
      }
    }
  }

}
