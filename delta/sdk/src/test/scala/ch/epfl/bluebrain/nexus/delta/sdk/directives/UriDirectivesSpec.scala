package ch.epfl.bluebrain.nexus.delta.sdk.directives

import akka.http.javadsl.server.InvalidRequiredValueForQueryParamRejection
import akka.http.scaladsl.model.MediaRanges.`*/*`
import akka.http.scaladsl.model.headers.Accept
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{MalformedQueryParamRejection, Route, ValidationRejection}
import ch.epfl.bluebrain.nexus.delta.kernel.search.TimeRange
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UrlUtils
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.schemas
import ch.epfl.bluebrain.nexus.delta.sdk.directives.UriDirectivesSpec.IntValue
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegment.{IriSegment, StringSegment}
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.kernel.search.Pagination.{FromPagination, SearchAfterPagination}
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.PaginationConfig
import ch.epfl.bluebrain.nexus.delta.sdk.utils.RouteHelpers
import ch.epfl.bluebrain.nexus.delta.sdk.{IndexingMode, OrderingFields}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Anonymous, Group, Subject, User}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ResourceRef}
import ch.epfl.bluebrain.nexus.testkit.scalatest.TestMatchers
import ch.epfl.bluebrain.nexus.testkit.{CirceLiteral, TestHelpers}
import org.scalatest.matchers.should.Matchers
import org.scalatest.{Inspectors, OptionValues}

import java.time.Instant
import java.util.UUID
import scala.util.Random

class UriDirectivesSpec
    extends RouteHelpers
    with Matchers
    with OptionValues
    with CirceLiteral
    with UriDirectives
    with TestMatchers
    with TestHelpers
    with Inspectors {

  implicit private val baseUri: BaseUri = BaseUri("http://localhost/base//", Label.unsafe("v1"))

  implicit private val paginationConfig: PaginationConfig =
    PaginationConfig(defaultSize = 10, sizeLimit = 20, fromLimit = 50)

  private val route: Route =
    (get & uriPrefix(baseUri.base)) {
      concat(
        (pathPrefix("search") & searchParams & pathEndOrSingleSlash) { case (deprecated, rev, createdBy, updatedBy) =>
          complete(s"'${deprecated.mkString}','${rev.mkString}','${createdBy.mkString}','${updatedBy.mkString}'")
        },
        (pathPrefix("pagination") & paginated & pathEndOrSingleSlash) {
          case FromPagination(from, size)               => complete(s"from='$from',size='$size'")
          case SearchAfterPagination(searchAfter, size) => complete(s"after='${searchAfter.noSpaces}',size='$size'")
        },
        (pathPrefix("label") & label & pathEndOrSingleSlash) { lb =>
          complete(lb.toString)
        },
        (pathPrefix("projectRef") & projectRef & pathEndOrSingleSlash) { ref =>
          complete(ref.toString)
        },
        (pathPrefix("uuid") & uuid & pathEndOrSingleSlash) { uuid =>
          complete(uuid.toString)
        },
        (pathPrefix("id") & idSegment & pathEndOrSingleSlash) {
          case IriSegment(iri)       => complete(s"iri='$iri'")
          case StringSegment(string) => complete(s"string='$string'")
        },
        (pathPrefix("idref") & idSegment) { id =>
          (idSegmentRef(id) & pathEndOrSingleSlash) {
            case IdSegmentRef.Latest(value)        => complete(s"latest=${value.asString}")
            case IdSegmentRef.Revision(value, rev) => complete(s"rev=${value.asString},$rev")
            case IdSegmentRef.Tag(value, tag)      => complete(s"tag=${value.asString},$tag")
          }
        },
        (pathPrefix("noRev") & noParameter("rev") & pathEndOrSingleSlash) {
          complete("noRev")
        },
        (pathPrefix("timerange") & createdAt & pathEndOrSingleSlash) {
          case TimeRange.Anytime             => complete("anytime")
          case TimeRange.After(value)        => complete(s"after=$value")
          case TimeRange.Before(value)       => complete(s"before=$value")
          case TimeRange.Between(start, end) => complete(s"between=$start,$end")
        },
        (pathPrefix("indexing") & indexingMode & pathEndOrSingleSlash) {
          case IndexingMode.Async => complete("async")
          case IndexingMode.Sync  => complete("sync")
        },
        (pathPrefix("jsonld") & jsonLdFormatOrReject & pathEndOrSingleSlash) { format =>
          complete(format.toString)
        }
      )
    }

  private def sortRoute(list: List[ResourceF[Int]]): Route =
    (get & uriPrefix(baseUri.base)) {
      (pathPrefix("ordering") & sort[IntValue] & pathEndOrSingleSlash) { implicit ordering =>
        complete(list.map(_.map(IntValue.apply)).sorted.map(_.value.value).mkString(","))
      }
    }

  "A route" should {

    "return a label" in {
      Get("/base/label/my") ~> Accept(`*/*`) ~> route ~> check {
        response.asString shouldEqual "my"
      }
    }

    "reject if label is wrongly formatted" in {
      Get("/base/label/oth@er") ~> Accept(`*/*`) ~> route ~> check {
        rejection shouldBe a[ValidationRejection]
      }
    }

    "return a project ref" in {
      Get("/base/projectRef/org/proj") ~> Accept(`*/*`) ~> route ~> check {
        response.asString shouldEqual "org/proj"
      }
    }

    "reject if project ref is wrongly formatted" in {
      Get("/base/projectRef/@rg/proj") ~> Accept(`*/*`) ~> route ~> check {
        rejection shouldBe a[ValidationRejection]
      }
    }

    "return a UUID" in {
      val uuid = UUID.randomUUID()
      Get(s"/base/uuid/$uuid") ~> Accept(`*/*`) ~> route ~> check {
        response.asString shouldEqual uuid.toString
      }
    }

    "reject if UUID wrongly formatted" in {
      Get("/base/uuid/other") ~> Accept(`*/*`) ~> route ~> check {
        handled shouldEqual false
      }
    }

    "pass if no rev query parameter is present" in {
      Get("/base/noRev?other=1") ~> Accept(`*/*`) ~> route ~> check {
        response.asString shouldEqual "noRev"
      }
    }

    "reject if rev query parameter is present" in {
      Get("/base/noRev?rev=1") ~> Accept(`*/*`) ~> route ~> check {
        rejection shouldBe a[MalformedQueryParamRejection]
      }
    }

    "reject if the time range is invalid" in {
      Get("/base/timerange?createdAt=FAIL") ~> Accept(`*/*`) ~> route ~> check {
        rejection shouldBe a[ValidationRejection]
      }
    }

    "return anytime if no time range is provided" in {
      Get("/base/timerange") ~> Accept(`*/*`) ~> route ~> check {
        response.asString shouldEqual "anytime"
      }
    }

    "return before if an end of range is provided" in {
      val end     = Instant.now()
      val encoded = UrlUtils.encode(end.toString)
      Get(s"/base/timerange?createdAt=*..$encoded") ~> Accept(`*/*`) ~> route ~> check {
        response.asString shouldEqual s"before=$end"
      }
    }

    "return after if an start of range is provided" in {
      val start   = Instant.now()
      val encoded = UrlUtils.encode(start.toString)
      Get(s"/base/timerange?createdAt=$encoded..*") ~> Accept(`*/*`) ~> route ~> check {
        response.asString shouldEqual s"after=$start"
      }
    }

    "return between is an start of range is provided" in {
      val start        = Instant.now()
      val end          = Instant.now().plusMillis(1_000_000L)
      val encodedStart = UrlUtils.encode(start.toString)
      val encodedEnd   = UrlUtils.encode(end.toString)
      Get(s"/base/timerange?createdAt=$encodedStart..$encodedEnd") ~> Accept(`*/*`) ~> route ~> check {
        response.asString shouldEqual s"between=$start,$end"
      }
    }

    "return async when no query param is present" in {
      Get("/base/indexing") ~> Accept(`*/*`) ~> route ~> check {
        response.asString shouldEqual "async"
      }
    }

    "return async when specified in query param" in {
      Get("/base/indexing?indexing=async") ~> Accept(`*/*`) ~> route ~> check {
        response.asString shouldEqual "async"
      }
    }

    "return sync when specified in query param" in {
      Get("/base/indexing?indexing=sync") ~> Accept(`*/*`) ~> route ~> check {
        response.asString shouldEqual "sync"
      }
    }

    "reject when other value is provided" in {
      Get("/base/indexing?indexing=other") ~> Accept(`*/*`) ~> route ~> check {
        rejection shouldBe a[MalformedQueryParamRejection]
      }
    }

    "return an IriSegment" in {
      val iri     = iri"http://example.com/a/b?rev=1#frag"
      val encoded = UrlUtils.encode(iri.toString)
      Get(s"/base/id/$encoded") ~> Accept(`*/*`) ~> route ~> check {
        response.asString shouldEqual s"iri='$iri'"
      }
    }

    "return a StringSegment" in {
      Get("/base/id/nxv:some") ~> Accept(`*/*`) ~> route ~> check {
        response.asString shouldEqual "string='nxv:some'"
      }
    }

    "return an IdSegmentRef" in {
      val iri     = iri"http://example.com/a/b"
      val encoded = UrlUtils.encode(iri.toString)
      Get(s"/base/idref/$encoded?rev=1") ~> Accept(`*/*`) ~> route ~> check {
        response.asString shouldEqual s"rev=$iri,1"
      }
      Get(s"/base/idref/$encoded?tag=mytag") ~> Accept(`*/*`) ~> route ~> check {
        response.asString shouldEqual s"tag=$iri,mytag"
      }
      Get(s"/base/idref/$encoded") ~> Accept(`*/*`) ~> route ~> check {
        response.asString shouldEqual s"latest=$iri"
      }
    }

    "return a jsonld expanded format" in {
      Get("/base/jsonld?format=expanded") ~> Accept(`*/*`) ~> route ~> check {
        response.asString shouldEqual "Expanded"
      }
    }

    "reject if jsonld format is wrongly formatted" in {
      Get("/base/jsonld?format=something") ~> Accept(`*/*`) ~> route ~> check {
        rejection shouldBe a[InvalidRequiredValueForQueryParamRejection]
      }
    }

    "return search parameters" in {
      val alicia   = User("alicia", Label.unsafe("myrealm"))
      val aliciaId = UrlUtils.encode(alicia.asIri.toString)
      val bob      = User("bob", Label.unsafe("myrealm"))
      val bobId    = UrlUtils.encode(bob.asIri.toString)

      Get(s"/base/search?deprecated=false&rev=2&createdBy=$aliciaId&updatedBy=$bobId") ~> Accept(
        `*/*`
      ) ~> route ~> check {
        response.asString shouldEqual s"'false','2','$alicia','$bob'"
      }

      Get(s"/base/search?deprecated=false&rev=2") ~> Accept(`*/*`) ~> route ~> check {
        response.asString shouldEqual "'false','2','',''"
      }
    }

    "reject on invalid search parameters" in {
      val group     = UrlUtils.encode(Group("mygroup", Label.unsafe("myrealm")).asIri.toString)
      val endpoints = List(
        "/base/search?deprecated=3",
        "/base/search?rev=false",
        "/base/search?createdBy=http%3A%2F%2Fexample.com%2Fwrong",
        s"/base/search?updatedBy=$group"
      )
      forAll(endpoints) { endpoint =>
        Get(endpoint) ~> Accept(`*/*`) ~> route ~> check {
          rejection shouldBe a[MalformedQueryParamRejection]
        }
      }
    }

    "return ordering" in {
      val bob  = User("bob", Label.unsafe("realm"))
      val list = Random.shuffle(
        List(
          resourceF(Anonymous, 1, deprecated = false, 1),
          resourceF(Anonymous, 2, deprecated = false, 2),
          resourceF(Anonymous, 2, deprecated = true, 3),
          resourceF(bob, 1, deprecated = false, 4),
          resourceF(bob, 3, deprecated = true, 5),
          resourceF(bob, 4, deprecated = false, 6)
        )
      )
      Get("/base/ordering?sort=_createdBy&sort=_rev&sort=_deprecated") ~> Accept(`*/*`) ~> sortRoute(list) ~> check {
        response.asString shouldEqual "1,2,3,4,5,6"
      }

      Get("/base/ordering?sort=-_createdBy&sort=-_rev&sort=_deprecated") ~> Accept(`*/*`) ~> sortRoute(list) ~> check {
        response.asString shouldEqual "6,5,4,2,3,1"
      }
      Get("/base/ordering?sort=-value") ~> Accept(`*/*`) ~> sortRoute(list) ~> check {
        response.asString shouldEqual "6,5,4,3,2,1"
      }
    }

    "reject on invalid ordering parameter" in {
      Get("/base/ordering?sort=_createdBy&sort=_rev&sort=something") ~> Accept(`*/*`) ~> sortRoute(
        List.empty
      ) ~> check {
        rejection shouldBe a[MalformedQueryParamRejection]
      }
    }

    "paginate with from and size" in {
      Get("/base/pagination?from=5&size=20") ~> Accept(`*/*`) ~> route ~> check {
        response.asString shouldEqual "from='5',size='20'"
      }
      Get("/base/pagination") ~> Accept(`*/*`) ~> route ~> check {
        response.asString shouldEqual "from='0',size='10'"
      }
    }

    "reject paginating with wrong size or from" in {
      val after = json"""["a", "b"]""".noSpaces
      forAll(
        List(
          "/base/pagination?from=5&size=30",
          "/base/pagination?from=60&size=20",
          s"/base/pagination?after=$after&from=10"
        )
      ) { endpoint =>
        Get(endpoint) ~> Accept(`*/*`) ~> route ~> check {
          rejection shouldBe a[MalformedQueryParamRejection]
        }
      }
    }

    "paginate with after and size" in {
      val json = json"""["a", "b"]"""
      Get(s"/base/pagination?after=${json.noSpaces}&size=20") ~> Accept(`*/*`) ~> route ~> check {
        response.asString shouldEqual s"after='${json.noSpaces}',size='20'"
      }
    }
  }

  def resourceF(createdBy: Subject, rev: Int, deprecated: Boolean, idx: Int): ResourceF[Int] =
    ResourceF(
      iri"http://localhost/${UUID.randomUUID()}",
      ResourceUris.permissions,
      rev,
      Set.empty,
      deprecated,
      Instant.EPOCH,
      createdBy,
      Instant.EPOCH,
      Anonymous,
      ResourceRef(schemas.permissions),
      idx
    )

}

object UriDirectivesSpec {
  final case class IntValue(value: Int)
  object IntValue {
    implicit val intValueOrderingFields: OrderingFields[IntValue] =
      OrderingFields { case "value" => Ordering[Int] on (_.value) }
  }
}
