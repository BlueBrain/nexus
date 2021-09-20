package ch.epfl.bluebrain.nexus.delta.plugins.statistics.routes

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.OAuth2BearerToken
import akka.http.scaladsl.server.{ExceptionHandler, RejectionHandler, Route}
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.statistics.model.PropertiesStatistics.Metadata
import ch.epfl.bluebrain.nexus.delta.plugins.statistics.model.StatisticsGraph.{Edge, EdgePath, Node}
import ch.epfl.bluebrain.nexus.delta.plugins.statistics.model.StatisticsRejection.WrappedProjectRejection
import ch.epfl.bluebrain.nexus.delta.plugins.statistics.model.{PropertiesStatistics, StatisticsGraph, StatisticsRejection}
import ch.epfl.bluebrain.nexus.delta.plugins.statistics.{ContextFixtures, Statistics}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.utils.RouteFixtures.s
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.schema
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.Permissions.resources
import ch.epfl.bluebrain.nexus.delta.sdk.cache.KeyValueStore
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ProjectGen
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.{RdfExceptionHandler, RdfRejectionHandler}
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.{Acl, AclAddress}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.{Anonymous, Authenticated, Group, User}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.{AuthToken, Caller}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectCountsCollection.ProjectCount
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRejection.ProjectNotFound
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, IdSegment, Label}
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.{AclSetup, ConfigFixtures, IdentitiesDummy, ProjectSetup}
import ch.epfl.bluebrain.nexus.delta.sdk.utils.RouteHelpers
import ch.epfl.bluebrain.nexus.delta.sdk.{ProgressesStatistics, ProjectsCountsDummy}
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.{ProjectionId, ProjectionProgress}
import ch.epfl.bluebrain.nexus.testkit._
import monix.bio.IO
import org.scalatest.matchers.should.Matchers
import org.scalatest.{CancelAfterFailure, Inspectors, OptionValues}

import java.time.Instant
import java.util.UUID

class StatisticsRoutesSpec
    extends RouteHelpers
    with Matchers
    with CirceLiteral
    with IOFixedClock
    with IOValues
    with OptionValues
    with TestMatchers
    with Inspectors
    with TestHelpers
    with ConfigFixtures
    with CancelAfterFailure
    with ContextFixtures {

  implicit val ordering: JsonKeyOrdering =
    JsonKeyOrdering.default(topKeys =
      List("@context", "@id", "@type", "reason", "details", "sourceId", "projectionId", "_total", "_results")
    )

  private val realm: Label              = Label.unsafe("wonderland")
  implicit private val alice: User      = User("alice", realm)
  implicit private val baseUri: BaseUri = BaseUri("http://localhost", Label.unsafe("v1"))
  implicit private val uuidF: UUIDF     = UUIDF.fixed(UUID.randomUUID())

  implicit private val caller: Caller =
    Caller(alice, Set(alice, Anonymous, Authenticated(realm), Group("group", realm)))

  private val identities = IdentitiesDummy(Map(AuthToken("alice") -> caller))
  private val asAlice    = addCredentials(OAuth2BearerToken("alice"))

  private val acls = AclSetup.init(Set(resources.read), Set(realm)).accepted

  private val org           = Label.unsafe("org")
  private val project       = ProjectGen.project("org", "project", uuid = UUID.randomUUID(), orgUuid = UUID.randomUUID())
  private val (_, projects) = ProjectSetup.init(org :: Nil, project :: Nil).accepted
  private val statistics    = new Statistics {

    override def relationships(projectRef: ProjectRef): IO[StatisticsRejection, StatisticsGraph] =
      IO.raiseWhen(projectRef != project.ref)(WrappedProjectRejection(ProjectNotFound(projectRef)))
        .as(
          StatisticsGraph(
            nodes = List(Node(schema.Person, "Person", 10), Node(schema + "Address", "Address", 5)),
            edges = List(Edge(schema.Person, schema + "Address", 5, Seq(EdgePath(schema + "address", "address"))))
          )
        )

    override def properties(projectRef: ProjectRef, tpe: IdSegment): IO[StatisticsRejection, PropertiesStatistics] =
      IO.raiseWhen(projectRef != project.ref)(WrappedProjectRejection(ProjectNotFound(projectRef)))
        .as(
          PropertiesStatistics(
            Metadata(schema.Person, "Person", 10),
            Seq(PropertiesStatistics(Metadata(schema + "address", "address", 5), Seq.empty))
          )
        )
  }

  private val projectsCounts = ProjectsCountsDummy(project.ref -> ProjectCount(10, 10, Instant.EPOCH))

  private val viewsProgressesCache                =
    KeyValueStore.localLRU[ProjectionId, ProjectionProgress[Unit]](10L).accepted
  private val statisticsProgress                  = new ProgressesStatistics(viewsProgressesCache, projectsCounts)
  implicit val rejectionHandler: RejectionHandler = RdfRejectionHandler.apply
  implicit val exceptionHandler: ExceptionHandler = RdfExceptionHandler.apply
  private val routes                              =
    Route.seal(new StatisticsRoutes(identities, acls, projects, statistics, statisticsProgress).routes)

  "Statistics routes" when {

    "dealing with relationships" should {
      "fail to fetch without resources/read permission" in {
        acls.append(Acl(AclAddress.Root, alice -> Set(resources.read)), 0L).accepted
        Get("/v1/statistics/org/project/relationships") ~> routes ~> check {
          response.status shouldEqual StatusCodes.Forbidden
          response.asJson shouldEqual jsonContentOf("errors/authorization-failed.json")
        }
      }

      "fetch" in {
        Get("/v1/statistics/org/project/relationships") ~> asAlice ~> routes ~> check {
          response.status shouldEqual StatusCodes.OK
          response.asJson shouldEqual jsonContentOf("routes/relationships.json")
        }
      }
    }

    "dealing with properties" should {

      "fail to fetch without resources/read permission" in {
        Get("/v1/statistics/org/project/properties/Person") ~> routes ~> check {
          response.status shouldEqual StatusCodes.Forbidden
          response.asJson shouldEqual jsonContentOf("errors/authorization-failed.json")
        }
      }

      "fetch" in {
        Get("/v1/statistics/org/project/properties/Person") ~> asAlice ~> routes ~> check {
          response.status shouldEqual StatusCodes.OK
          response.asJson shouldEqual jsonContentOf("routes/properties.json")
        }
      }
    }

    "dealing with stream progress" should {

      "fail to fetch without resources/read permission" in {
        Get("/v1/statistics/org/project/progress") ~> routes ~> check {
          response.status shouldEqual StatusCodes.Forbidden
          response.asJson shouldEqual jsonContentOf("errors/authorization-failed.json")
        }
      }

      "fetch" in {
        Get("/v1/statistics/org/project/progress") ~> asAlice ~> routes ~> check {
          response.status shouldEqual StatusCodes.OK
          response.asJson shouldEqual jsonContentOf("routes/statistics.json")
        }
      }
    }

  }

}
