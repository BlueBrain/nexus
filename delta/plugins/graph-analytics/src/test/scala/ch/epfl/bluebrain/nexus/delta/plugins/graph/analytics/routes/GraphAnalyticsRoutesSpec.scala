package ch.epfl.bluebrain.nexus.delta.plugins.graph.analytics.routes

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.OAuth2BearerToken
import akka.http.scaladsl.server.Route
import ch.epfl.bluebrain.nexus.delta.plugins.graph.analytics.model.AnalyticsGraph.{Edge, EdgePath, Node}
import ch.epfl.bluebrain.nexus.delta.plugins.graph.analytics.model.GraphAnalyticsRejection.ProjectContextRejection
import ch.epfl.bluebrain.nexus.delta.plugins.graph.analytics.model.PropertiesStatistics.Metadata
import ch.epfl.bluebrain.nexus.delta.plugins.graph.analytics.model.{AnalyticsGraph, GraphAnalyticsRejection, PropertiesStatistics}
import ch.epfl.bluebrain.nexus.delta.plugins.graph.analytics.{ContextFixtures, GraphAnalytics, contexts}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.schema
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.ProgressesStatistics
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclSimpleCheck
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.cache.KeyValueStore
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaSchemeDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ProjectGen
import ch.epfl.bluebrain.nexus.delta.sdk.identities.IdentitiesDummy
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegment
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions.resources
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContext.ContextRejection
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ProjectRejection.ProjectNotFound
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.{ProjectRejection, ProjectStatistics}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.utils.BaseRouteSpec
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Anonymous, Authenticated, Group}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.{ProjectionId, ProjectionProgress}
import monix.bio.IO
import org.scalatest.CancelAfterFailure

import java.time.Instant
import java.util.UUID

class GraphAnalyticsRoutesSpec
    extends BaseRouteSpec
    with CancelAfterFailure
    with ContextFixtures {

  // TODO: sort out how we handle this in tests
  implicit override val rcr: RemoteContextResolution =
    RemoteContextResolution.fixed(
      contexts.relationships         -> jsonContentOf("contexts/relationships.json").topContextValueOrEmpty,
      contexts.properties            -> jsonContentOf("contexts/properties.json").topContextValueOrEmpty,
      Vocabulary.contexts.statistics -> jsonContentOf("contexts/statistics.json").topContextValueOrEmpty,
      Vocabulary.contexts.error      -> jsonContentOf("contexts/error.json").topContextValueOrEmpty
    )

  implicit private val caller: Caller =
    Caller(alice, Set(alice, Anonymous, Authenticated(realm), Group("group", realm)))

  private val identities = IdentitiesDummy(caller)
  private val asAlice    = addCredentials(OAuth2BearerToken("alice"))

  private val aclCheck = AclSimpleCheck().accepted
  private val project  = ProjectGen.project("org", "project", uuid = UUID.randomUUID(), orgUuid = UUID.randomUUID())

  private def projectNotFound(projectRef: ProjectRef) = ProjectContextRejection(
    ContextRejection(ProjectNotFound(projectRef).asInstanceOf[ProjectRejection])
  )

  private val graphAnalytics = new GraphAnalytics {

    override def relationships(projectRef: ProjectRef): IO[GraphAnalyticsRejection, AnalyticsGraph] =
      IO.raiseWhen(projectRef != project.ref)(projectNotFound(projectRef))
        .as(
          AnalyticsGraph(
            nodes = List(Node(schema.Person, "Person", 10), Node(schema + "Address", "Address", 5)),
            edges = List(Edge(schema.Person, schema + "Address", 5, Seq(EdgePath(schema + "address", "address"))))
          )
        )

    override def properties(projectRef: ProjectRef, tpe: IdSegment): IO[GraphAnalyticsRejection, PropertiesStatistics] =
      IO.raiseWhen(projectRef != project.ref)(projectNotFound(projectRef))
        .as(
          PropertiesStatistics(
            Metadata(schema.Person, "Person", 10),
            Seq(PropertiesStatistics(Metadata(schema + "address", "address", 5), Seq.empty))
          )
        )
  }

  private val viewsProgressesCache                =
    KeyValueStore.localLRU[ProjectionId, ProjectionProgress[Unit]](10L).accepted
  private val graphAnalyticsProgress              = new ProgressesStatistics(
    viewsProgressesCache,
    ioFromMap(project.ref -> ProjectStatistics(10, 10, Instant.EPOCH))
  )

  private val routes                              =
    Route.seal(
      new GraphAnalyticsRoutes(
        identities,
        aclCheck,
        graphAnalytics,
        graphAnalyticsProgress,
        DeltaSchemeDirectives.empty
      ).routes
    )

  "graph analytics routes" when {

    "dealing with relationships" should {
      "fail to fetch without resources/read permission" in {
        aclCheck.append(AclAddress.Root, alice -> Set(resources.read)).accepted
        Get("/v1/graph-analytics/org/project/relationships") ~> routes ~> check {
          response.status shouldEqual StatusCodes.Forbidden
          response.asJson shouldEqual jsonContentOf("errors/authorization-failed.json")
        }
      }

      "fetch" in {
        Get("/v1/graph-analytics/org/project/relationships") ~> asAlice ~> routes ~> check {
          response.status shouldEqual StatusCodes.OK
          response.asJson shouldEqual jsonContentOf("routes/relationships.json")
        }
      }
    }

    "dealing with properties" should {

      "fail to fetch without resources/read permission" in {
        Get("/v1/graph-analytics/org/project/properties/Person") ~> routes ~> check {
          response.status shouldEqual StatusCodes.Forbidden
          response.asJson shouldEqual jsonContentOf("errors/authorization-failed.json")
        }
      }

      "fetch" in {
        Get("/v1/graph-analytics/org/project/properties/Person") ~> asAlice ~> routes ~> check {
          response.status shouldEqual StatusCodes.OK
          response.asJson shouldEqual jsonContentOf("routes/properties.json")
        }
      }
    }

    "dealing with stream progress" should {

      "fail to fetch without resources/read permission" in {
        Get("/v1/graph-analytics/org/project/progress") ~> routes ~> check {
          response.status shouldEqual StatusCodes.Forbidden
          response.asJson shouldEqual jsonContentOf("errors/authorization-failed.json")
        }
      }

      "fetch" in {
        Get("/v1/graph-analytics/org/project/progress") ~> asAlice ~> routes ~> check {
          response.status shouldEqual StatusCodes.OK
          response.asJson shouldEqual jsonContentOf("routes/statistics.json")
        }
      }
    }

  }

}
