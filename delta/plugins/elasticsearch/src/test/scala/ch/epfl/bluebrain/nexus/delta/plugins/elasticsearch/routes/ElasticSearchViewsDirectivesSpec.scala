package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.routes

import akka.http.scaladsl.model.MediaRanges.`*/*`
import akka.http.scaladsl.model.headers.Accept
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UrlUtils
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ResourcesSearchParams
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.projects.Projects.FetchProject
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ProjectGen
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.User
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ApiMappings
import ch.epfl.bluebrain.nexus.delta.sdk.utils.RouteHelpers
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.testkit.{CirceLiteral, IOValues, TestHelpers, TestMatchers}
import monix.bio.IO
import monix.execution.Scheduler
import org.scalatest.matchers.should.Matchers
import org.scalatest.{Inspectors, OptionValues}

class ElasticSearchViewsDirectivesSpec
    extends RouteHelpers
    with Matchers
    with OptionValues
    with CirceLiteral
    with ElasticSearchViewsDirectives
    with IOValues
    with TestMatchers
    with TestHelpers
    with Inspectors {

  implicit private val baseUri: BaseUri = BaseUri("http://localhost", Label.unsafe("v1"))
  implicit private val sc: Scheduler    = Scheduler.global

  private val mappings                            = ApiMappings("alias" -> (nxv + "alias"), "nxv" -> nxv.base)
  private val vocab                               = iri"http://localhost/vocab/"
  private val base                                = iri"http://localhost/base/"
  implicit private val fetchProject: FetchProject = ref =>
    IO.pure(
      ProjectGen.project(ref.organization.value, ref.project.value, mappings = mappings, vocab = vocab, base = base)
    )

  private val route: Route =
    get {
      concat(
        (pathPrefix("sort") & sortList & pathEndOrSingleSlash) { list =>
          complete(list.values.map(_.toString).mkString(","))
        },
        (pathPrefix("search") & projectRef & pathEndOrSingleSlash) { ref =>
          searchParameters(ref).apply {
            case ResourcesSearchParams(id, deprecated, rev, createdBy, updatedBy, types, schema, q) =>
              complete(
                s"'${id.mkString}','${deprecated.mkString}','${rev.mkString}','${createdBy.mkString}','${updatedBy.mkString}','${types
                  .mkString("|")
                  .mkString}','${schema.mkString}','${q.mkString}'"
              )

          }
        }
      )
    }

  "A route" should {

    "return the sort parameters" in {
      Get("/sort?sort=+deprecated&sort=-@id&sort=_createdBy") ~> Accept(`*/*`) ~> route ~> check {
        response.asString shouldEqual "deprecated,-@id,_createdBy"
      }
    }

    "return the search parameters" in {
      val alicia   = User("alicia", Label.unsafe("myrealm"))
      val aliciaId = UrlUtils.encode(alicia.asIri.toString)
      val bob      = User("bob", Label.unsafe("myrealm"))
      val bobId    = UrlUtils.encode(bob.asIri.toString)

      Get(
        s"/search/org/project?id=myId&deprecated=false&rev=2&createdBy=$aliciaId&updatedBy=$bobId&type=A&type=B&type=-C&schema=C&q=something"
      ) ~> Accept(`*/*`) ~> route ~> check {
        response.asString shouldEqual s"'${base}myId','false','2','$alicia','$bob','IncludedType(${vocab}A)|IncludedType(${vocab}B)|ExcludedType(${vocab}C)','${base}C','something'"
      }

      Get("/search/org/project") ~> Accept(`*/*`) ~> route ~> check {
        response.asString shouldEqual "'','','','','','','',''"
      }
    }
  }

}
