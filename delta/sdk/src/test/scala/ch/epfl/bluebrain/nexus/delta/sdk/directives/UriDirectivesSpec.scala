package ch.epfl.bluebrain.nexus.delta.sdk.directives

import akka.http.javadsl.server.InvalidRequiredValueForQueryParamRejection
import akka.http.scaladsl.model.MediaRanges.`*/*`
import akka.http.scaladsl.model.headers.Accept
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{MalformedQueryParamRejection, Route, ValidationRejection}
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UrlUtils
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{nxv, schemas}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.OrderingFields
import ch.epfl.bluebrain.nexus.delta.sdk.Projects.{FetchProject, FetchProjectByUuid}
import ch.epfl.bluebrain.nexus.delta.sdk.directives.UriDirectivesSpec.IntValue
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ProjectGen
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegment.{IriSegment, StringSegment}
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.{Anonymous, Group, Subject, User}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ApiMappings
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRejection.ProjectNotFound
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.Pagination.{FromPagination, SearchAfterPagination}
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.PaginationConfig
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.utils.RouteHelpers
import ch.epfl.bluebrain.nexus.testkit.{CirceLiteral, IOValues, TestHelpers, TestMatchers}
import monix.bio.IO
import monix.execution.Scheduler
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
    with IOValues
    with TestMatchers
    with TestHelpers
    with Inspectors {

  implicit private val baseUri: BaseUri = BaseUri("http://localhost/base//", Label.unsafe("v1"))
  implicit private val sc: Scheduler    = Scheduler.global
  private val schemaView                = nxv + "schema"

  private val mappings                   = ApiMappings("alias" -> (nxv + "alias"), "nxv" -> nxv.base, "view" -> schemaView)
  private val vocab                      = iri"http://localhost/vocab/"
  private val fetchProject: FetchProject = ref =>
    IO.pure(ProjectGen.project(ref.organization.value, ref.project.value, mappings = mappings, vocab = vocab))

  private val fetchProjectByUuid: FetchProjectByUuid = uuid => IO.raiseError(ProjectNotFound(uuid))

  implicit private val orderingKeys: JsonKeyOrdering = JsonKeyOrdering.alphabetical
  implicit private val rcr: RemoteContextResolution  = RemoteContextResolution.never

  implicit private val paginationConfig: PaginationConfig =
    PaginationConfig(defaultSize = 10, sizeLimit = 20, fromLimit = 50)

  private val route: Route =
    (get & uriPrefix(baseUri.base)) {
      concat(
        (pathPrefix("search") & searchParams & pathEndOrSingleSlash) { case (deprecated, rev, createdBy, updatedBy) =>
          complete(s"'${deprecated.mkString}','${rev.mkString}','${createdBy.mkString}','${updatedBy.mkString}'")
        },
        (pathPrefix("types") & projectRef & pathEndOrSingleSlash) { implicit projectRef =>
          types(fetchProject).apply { types =>
            complete(types.mkString(","))
          }
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
        (pathPrefix("jsonld") & jsonLdFormatOrReject & pathEndOrSingleSlash) { format =>
          complete(format.toString)
        },
        baseUriPrefix(baseUri.prefix) {
          replaceUri("views", schemaView, fetchProject, fetchProjectByUuid).apply {
            concat(
              (pathPrefix("views") & projectRef & idSegment & pathPrefix("other") & pathEndOrSingleSlash) {
                (project, id) =>
                  complete(s"project='$project',id='$id'")
              },
              pathPrefix("other") {
                complete("other")
              }
            )
          }
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
      val aliciaId = UrlUtils.encode(alicia.id.toString)
      val bob      = User("bob", Label.unsafe("myrealm"))
      val bobId    = UrlUtils.encode(bob.id.toString)

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
      val group     = UrlUtils.encode(Group("mygroup", Label.unsafe("myrealm")).id.toString)
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

    "return expanded types" in {
      Get("/base/types/org/proj?type=a&type=alias&type=nxv:rev") ~> Accept(`*/*`) ~> route ~> check {
        response.asString shouldEqual s"${nxv.rev.iri},${nxv + "alias"},http://localhost/vocab/a"
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

    "return a project and id when redirecting through the _ schema id" in {
      val endpoints = List("/base/v1/resources/org/proj/_/myid/other", "/base/v1/views/org/proj/myid/other")
      forAll(endpoints) { endpoint =>
        Get(endpoint) ~> Accept(`*/*`) ~> route ~> check {
          response.asString shouldEqual "project='org/proj',id='myid'"
        }
      }
    }

    "return a project and id when redirecting through the schema id" in {
      val encoded   = UrlUtils.encode(schemaView.toString)
      val endpoints =
        List("/base/v1/resources/org/proj/view/myid/other", s"/base/v1/resources/org/proj/$encoded/myid/other")
      forAll(endpoints) { endpoint =>
        Get(endpoint) ~> Accept(`*/*`) ~> route ~> check {
          response.asString shouldEqual "project='org/proj',id='myid'"
        }
      }
    }

    "reject when redirecting" in {
      Get("/base/v1/resources/org/proj/wrong_schema/myid/other") ~> Accept(`*/*`) ~> route ~> check {
        handled shouldEqual false
      }
    }

    "return the other route when redirecting is not being affected" in {
      Get("/base/v1/other") ~> Accept(`*/*`) ~> route ~> check {
        response.asString shouldEqual "other"
      }
    }
  }

  def resourceF(createdBy: Subject, rev: Long, deprecated: Boolean, idx: Int): ResourceF[Int] =
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
