package ch.epfl.bluebrain.nexus.delta.sdk.directives

import akka.http.scaladsl.model.MediaRanges.`*/*`
import akka.http.scaladsl.model.headers.Accept
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UrlUtils
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.{ApiMappings, ProjectContext}
import ch.epfl.bluebrain.nexus.delta.sdk.utils.RouteHelpers
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef}
import ch.epfl.bluebrain.nexus.testkit.{CirceLiteral, IOValues, TestHelpers, TestMatchers}
import monix.bio.UIO
import monix.execution.Scheduler
import org.scalatest.matchers.should.Matchers
import org.scalatest.{Inspectors, OptionValues}

import java.util.UUID

class DeltaSchemeDirectivesSpec
    extends RouteHelpers
    with Matchers
    with OptionValues
    with CirceLiteral
    with UriDirectives
    with IOValues
    with TestMatchers
    with TestHelpers
    with Inspectors {

  implicit private val sc: Scheduler = Scheduler.global

  implicit private val baseUri: BaseUri = BaseUri("http://localhost/base//", Label.unsafe("v1"))
  private val schemaView                = nxv + "schema"

  private val mappings = ApiMappings("alias" -> (nxv + "alias"), "nxv" -> nxv.base, "view" -> schemaView)
  private val vocab    = iri"http://localhost/vocab/"

  private val fetchContext = (_: ProjectRef) => UIO.pure(ProjectContext.unsafe(mappings, nxv.base, vocab))

  private val fetchOrgByUuid     = (_: UUID) => UIO.none
  private val fetchProjectByUuid = (_: UUID) => UIO.none

  private val schemeDirectives = new DeltaSchemeDirectives(fetchContext, fetchOrgByUuid, fetchProjectByUuid)

  private val route: Route =
    (get & uriPrefix(baseUri.base)) {
      import schemeDirectives._
      concat(
        (pathPrefix("types") & projectRef & pathEndOrSingleSlash) { implicit projectRef =>
          types.apply { types =>
            complete(types.mkString(","))
          }
        },
        baseUriPrefix(baseUri.prefix) {
          replaceUri("views", schemaView).apply {
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

  "A route" should {

    "return expanded types" in {
      Get("/base/types/org/proj?type=a&type=alias&type=nxv:rev") ~> Accept(`*/*`) ~> route ~> check {
        response.asString shouldEqual s"${nxv.rev.iri},${nxv + "alias"},http://localhost/vocab/a"
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

}
