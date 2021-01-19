package ch.epfl.bluebrain.nexus.delta.sdk.directives

import akka.http.javadsl.server.InvalidRequiredValueForQueryParamRejection
import akka.http.scaladsl.model.MediaRanges.`*/*`
import akka.http.scaladsl.model.headers.Accept
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{MalformedQueryParamRejection, Route, ValidationRejection}
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UrlUtils
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.Projects.FetchProject
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ProjectGen
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegment.{IriSegment, StringSegment}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.{Group, User}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ApiMappings
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Label}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.utils.RouteHelpers
import ch.epfl.bluebrain.nexus.testkit.{CirceLiteral, IOValues, TestHelpers, TestMatchers}
import monix.bio.IO
import monix.execution.Scheduler
import org.scalatest.matchers.should.Matchers
import org.scalatest.{Inspectors, OptionValues}

import java.util.UUID

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

  implicit private val baseUri: BaseUri = BaseUri("http://localhost", Label.unsafe("v1"))
  implicit private val sc: Scheduler    = Scheduler.global

  private val mappings                            = ApiMappings(Map("alias" -> (nxv + "alias"), "nxv" -> nxv.base))
  private val vocab                               = iri"http://localhost/vocab/"
  implicit private val fetchProject: FetchProject = ref =>
    IO.pure(
      ProjectGen.resourceFor(
        ProjectGen.project(ref.organization.value, ref.project.value, mappings = mappings, vocab = vocab)
      )
    )

  private val route: Route =
    get {
      concat(
        (pathPrefix("search") & searchParams & pathEndOrSingleSlash) { case (deprecated, rev, createdBy, updatedBy) =>
          complete(s"'${deprecated.mkString}','${rev.mkString}','${createdBy.mkString}','${updatedBy.mkString}'")
        },
        (pathPrefix("types") & projectRef & pathEndOrSingleSlash) { implicit projectRef =>
          types.apply { types =>
            complete(types.mkString(","))
          }
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
        (pathPrefix("noRev") & noParameter("rev") & pathEndOrSingleSlash) {
          complete("noRev")
        },
        (pathPrefix("jsonld") & jsonLdFormat & pathEndOrSingleSlash) { format =>
          complete(format.toString)
        }
      )
    }

  "A route" should {

    "return a label" in {
      Get("/label/my") ~> Accept(`*/*`) ~> route ~> check {
        response.asString shouldEqual "my"
      }
    }

    "reject if label is wrongly formatted" in {
      Get("/label/oth@er") ~> Accept(`*/*`) ~> route ~> check {
        rejection shouldBe a[ValidationRejection]
      }
    }

    "return a project ref" in {
      Get("/projectRef/org/proj") ~> Accept(`*/*`) ~> route ~> check {
        response.asString shouldEqual "org/proj"
      }
    }

    "reject if project ref is wrongly formatted" in {
      Get("/projectRef/@rg/proj") ~> Accept(`*/*`) ~> route ~> check {
        rejection shouldBe a[ValidationRejection]
      }
    }

    "return a UUID" in {
      val uuid = UUID.randomUUID()
      Get(s"/uuid/$uuid") ~> Accept(`*/*`) ~> route ~> check {
        response.asString shouldEqual uuid.toString
      }
    }

    "reject if UUID wrongly formatted" in {
      Get("/uuid/other") ~> Accept(`*/*`) ~> route ~> check {
        rejection shouldBe a[ValidationRejection]
      }
    }

    "pass if no rev query parameter is present" in {
      Get("/noRev?other=1") ~> Accept(`*/*`) ~> route ~> check {
        response.asString shouldEqual "noRev"
      }
    }

    "reject if rev query parameter is present" in {
      Get("/noRev?rev=1") ~> Accept(`*/*`) ~> route ~> check {
        rejection shouldBe a[MalformedQueryParamRejection]
      }
    }

    "return an IriSegment" in {
      val iri     = iri"http://example.com/a/b?rev=1#frag"
      val encoded = UrlUtils.encode(iri.toString)
      Get(s"/id/$encoded") ~> Accept(`*/*`) ~> route ~> check {
        response.asString shouldEqual s"iri='$iri'"
      }
    }

    "return a StringSegment" in {
      Get("/id/nxv:some") ~> Accept(`*/*`) ~> route ~> check {
        response.asString shouldEqual "string='nxv:some'"
      }
    }

    "return a jsonld expanded format" in {
      Get("/jsonld?format=expanded") ~> Accept(`*/*`) ~> route ~> check {
        response.asString shouldEqual "Expanded"
      }
    }

    "reject if jsonld format is wrongly formatted" in {
      Get("/jsonld?format=something") ~> Accept(`*/*`) ~> route ~> check {
        rejection shouldBe a[InvalidRequiredValueForQueryParamRejection]
      }
    }

    "return search parameters" in {
      val alicia   = User("alicia", Label.unsafe("myrealm"))
      val aliciaId = UrlUtils.encode(alicia.id.toString)
      val bob      = User("bob", Label.unsafe("myrealm"))
      val bobId    = UrlUtils.encode(bob.id.toString)

      Get(s"/search?deprecated=false&rev=2&createdBy=$aliciaId&updatedBy=$bobId") ~> Accept(`*/*`) ~> route ~> check {
        response.asString shouldEqual s"'false','2','$alicia','$bob'"
      }

      Get(s"/search?deprecated=false&rev=2") ~> Accept(`*/*`) ~> route ~> check {
        response.asString shouldEqual "'false','2','',''"
      }
    }

    "reject on invalid search parameters" in {
      val group     = UrlUtils.encode(Group("mygroup", Label.unsafe("myrealm")).id.toString)
      val endpoints = List(
        "/search?deprecated=3",
        "/search?rev=false",
        "/search?createdBy=http%3A%2F%2Fexample.com%2Fwrong",
        s"/search?updatedBy=$group"
      )
      forAll(endpoints) { endpoint =>
        Get(endpoint) ~> Accept(`*/*`) ~> route ~> check {
          rejection shouldBe a[MalformedQueryParamRejection]
        }
      }
    }

    "return expanded types" in {
      Get("/types/org/proj?type=a&type=alias&type=nxv:rev") ~> Accept(`*/*`) ~> route ~> check {
        response.asString shouldEqual s"${nxv.rev.iri},${nxv + "alias"},http://localhost/vocab/a"
      }
    }
  }

}
