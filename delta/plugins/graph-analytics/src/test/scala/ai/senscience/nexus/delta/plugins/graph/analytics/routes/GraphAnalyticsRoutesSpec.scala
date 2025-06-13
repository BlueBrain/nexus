package ai.senscience.nexus.delta.plugins.graph.analytics.routes

import ai.senscience.nexus.delta.plugins.graph.analytics.model.AnalyticsGraph.{Edge, EdgePath, Node}
import ai.senscience.nexus.delta.plugins.graph.analytics.model.PropertiesStatistics.Metadata
import ai.senscience.nexus.delta.plugins.graph.analytics.model.{AnalyticsGraph, PropertiesStatistics}
import ai.senscience.nexus.delta.plugins.graph.analytics.{contexts, permissions, GraphAnalytics}
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Route
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.schema
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclSimpleCheck
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ProjectGen
import ch.epfl.bluebrain.nexus.delta.sdk.identities.IdentitiesDummy
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegment
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions.resources
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ProjectRejection
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ProjectRejection.ProjectNotFound
import ch.epfl.bluebrain.nexus.delta.sdk.syntax.*
import ch.epfl.bluebrain.nexus.delta.sdk.utils.BaseRouteSpec
import ch.epfl.bluebrain.nexus.delta.sourcing.ProgressStatistics
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import org.scalatest.CancelAfterFailure

import java.time.Instant
import java.util.UUID

class GraphAnalyticsRoutesSpec extends BaseRouteSpec with CancelAfterFailure {

  // TODO: sort out how we handle this in tests
  implicit override def rcr: RemoteContextResolution =
    RemoteContextResolution.fixed(
      contexts.relationships         -> jsonContentOf("contexts/relationships.json").topContextValueOrEmpty,
      contexts.properties            -> jsonContentOf("contexts/properties.json").topContextValueOrEmpty,
      Vocabulary.contexts.statistics -> jsonContentOf("contexts/statistics.json").topContextValueOrEmpty,
      Vocabulary.contexts.error      -> jsonContentOf("contexts/error.json").topContextValueOrEmpty
    )

  private val identities = IdentitiesDummy.fromUsers(alice)

  private val aclCheck = AclSimpleCheck().accepted
  private val project  = ProjectGen.project("org", "project", uuid = UUID.randomUUID(), orgUuid = UUID.randomUUID())

  private def projectNotFound(projectRef: ProjectRef) = ProjectNotFound(projectRef).asInstanceOf[ProjectRejection]

  private val graphAnalytics = new GraphAnalytics {

    override def relationships(projectRef: ProjectRef): IO[AnalyticsGraph] =
      IO.raiseWhen(projectRef != project.ref)(projectNotFound(projectRef))
        .as(
          AnalyticsGraph(
            nodes = List(Node(schema.Person, "Person", 10), Node(schema + "Address", "Address", 5)),
            edges = List(Edge(schema.Person, schema + "Address", 5, Seq(EdgePath(schema + "address", "address"))))
          )
        )

    override def properties(projectRef: ProjectRef, tpe: IdSegment): IO[PropertiesStatistics] =
      IO.raiseWhen(projectRef != project.ref)(projectNotFound(projectRef))
        .as(
          PropertiesStatistics(
            Metadata(schema.Person, "Person", 10),
            Seq(PropertiesStatistics(Metadata(schema + "address", "address", 5), Seq.empty))
          )
        )
  }

  private val viewQueryResponse = json"""{"key": "value"}"""

  private lazy val routes =
    Route.seal(
      new GraphAnalyticsRoutes(
        identities,
        aclCheck,
        graphAnalytics,
        _ => IO.pure(ProgressStatistics(0L, 0L, 0L, 10L, Some(Instant.EPOCH), None)),
        (_, _, _) => IO.pure(viewQueryResponse)
      ).routes
    )

  "graph analytics routes" when {

    "dealing with relationships" should {
      "fail to fetch without resources/read permission" in {
        aclCheck.append(AclAddress.Root, alice -> Set(resources.read)).accepted
        Get("/v1/graph-analytics/org/project/relationships") ~> routes ~> check {
          response.shouldBeForbidden
        }
      }

      "fetch" in {
        Get("/v1/graph-analytics/org/project/relationships") ~> as(alice) ~> routes ~> check {
          response.status shouldEqual StatusCodes.OK
          response.asJson shouldEqual jsonContentOf("routes/relationships.json")
        }
      }
    }

    "dealing with properties" should {

      "fail to fetch without resources/read permission" in {
        Get("/v1/graph-analytics/org/project/properties/Person") ~> routes ~> check {
          response.shouldBeForbidden
        }
      }

      "fetch" in {
        Get("/v1/graph-analytics/org/project/properties/Person") ~> as(alice) ~> routes ~> check {
          response.status shouldEqual StatusCodes.OK
          response.asJson shouldEqual jsonContentOf("routes/properties.json")
        }
      }
    }

    "dealing with stream progress" should {

      "fail to fetch without resources/read permission" in {
        Get("/v1/graph-analytics/org/project/statistics") ~> routes ~> check {
          response.shouldBeForbidden
        }
      }

      "fetch" in {
        Get("/v1/graph-analytics/org/project/statistics") ~> as(alice) ~> routes ~> check {
          response.status shouldEqual StatusCodes.OK
          response.asJson shouldEqual jsonContentOf("routes/statistics.json")
        }
      }
    }

    "querying" should {

      val query = json"""{ "query": { "match_all": {} } }"""

      "fail without authorization" in {
        Post("/v1/graph-analytics/org/project/_search", query.toEntity) ~> as(alice) ~> routes ~> check {
          response.shouldBeForbidden
        }
      }

      "succeed" in {
        aclCheck.append(AclAddress.Root, alice -> Set(permissions.query)).accepted
        Post("/v1/graph-analytics/org/project/_search", query.toEntity) ~> as(alice) ~> routes ~> check {
          response.status shouldEqual StatusCodes.OK
          response.asJson shouldEqual viewQueryResponse
        }
      }

    }

  }

}
