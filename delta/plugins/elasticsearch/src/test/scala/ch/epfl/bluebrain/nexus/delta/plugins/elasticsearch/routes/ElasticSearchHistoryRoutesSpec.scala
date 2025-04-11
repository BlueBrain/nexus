package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.routes

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Route
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UrlUtils
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.metrics.FetchHistory
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.iriStringContextSyntax
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions.resources
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Anonymous
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import io.circe.JsonObject
import io.circe.syntax.KeyOps

class ElasticSearchHistoryRoutesSpec extends ElasticSearchViewsRoutesFixtures {

  private val myId        = iri"""https://bbp.epfl.ch/data/myid"""
  private val myIdEncoded = UrlUtils.encode(myId.toString)

  private val eventMetricsQuery = new FetchHistory {
    override def history(project: ProjectRef, id: IriOrBNode.Iri): IO[SearchResults[JsonObject]] = {
      IO.pure(SearchResults(1L, List(JsonObject("project" := project, "@id" := id))))
    }
  }

  private lazy val routes =
    Route.seal(
      new ElasticSearchHistoryRoutes(
        identities,
        aclCheck,
        eventMetricsQuery
      ).routes
    )

  "Fail to access the history of a resource if the user has no access" in {
    Get(s"/history/resources/org/proj/$myIdEncoded") ~> routes ~> check {
      response.status shouldEqual StatusCodes.Forbidden
    }
  }

  "Return the history if no access if the user has access" in {
    aclCheck.append(AclAddress.Root, Anonymous -> Set(resources.read)).accepted
    Get(s"/history/resources/org/proj/$myIdEncoded") ~> routes ~> check {
      response.status shouldEqual StatusCodes.OK
      val expected =
        json"""{ "_total" : 1, "_results" : [{ "@id" : "https://bbp.epfl.ch/data/myid", "project" : "org/proj" } ]}"""
      response.asJson shouldEqual expected
    }
  }

}
