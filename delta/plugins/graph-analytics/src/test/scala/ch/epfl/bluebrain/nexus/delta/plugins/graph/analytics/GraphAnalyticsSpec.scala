package ch.epfl.bluebrain.nexus.delta.plugins.graph.analytics

import akka.actor.ActorSystem
import akka.testkit.TestKit
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategyConfig
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.{ElasticSearchBulk, ElasticSearchClient}
import ch.epfl.bluebrain.nexus.delta.plugins.graph.analytics.config.GraphAnalyticsConfig.TermAggregationsConfig
import ch.epfl.bluebrain.nexus.delta.plugins.graph.analytics.model.PropertiesStatistics.Metadata
import ch.epfl.bluebrain.nexus.delta.plugins.graph.analytics.model.AnalyticsGraph.{Edge, EdgePath, Node}
import ch.epfl.bluebrain.nexus.delta.plugins.graph.analytics.model.{AnalyticsGraph, PropertiesStatistics}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.schema
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ProjectGen
import ch.epfl.bluebrain.nexus.delta.sdk.http.{HttpClient, HttpClientConfig, HttpClientWorthRetry}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.{Anonymous, Subject}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Label}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.{ConfigFixtures, ProjectSetup}
import ch.epfl.bluebrain.nexus.testkit.elasticsearch.ElasticSearchDocker
import ch.epfl.bluebrain.nexus.testkit.elasticsearch.ElasticSearchDocker._
import ch.epfl.bluebrain.nexus.testkit.{IOFixedClock, IOValues, TestHelpers}
import monix.execution.Scheduler
import org.scalatest.DoNotDiscover
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Millis, Span}
import org.scalatest.wordspec.AnyWordSpecLike

import java.util.UUID
import scala.concurrent.duration._

@DoNotDiscover
class GraphAnalyticsSpec(docker: ElasticSearchDocker)
    extends TestKit(ActorSystem("GraphAnalyticsSpec"))
    with AnyWordSpecLike
    with Matchers
    with TestHelpers
    with IOValues
    with IOFixedClock
    with ConfigFixtures
    with Eventually {

  implicit override def patienceConfig: PatienceConfig = PatienceConfig(10.seconds, Span(10, Millis))

  implicit val sc: Scheduler             = Scheduler.global
  implicit val cfg: HttpClientConfig     =
    HttpClientConfig(RetryStrategyConfig.AlwaysGiveUp, HttpClientWorthRetry.never, true)
  implicit private val baseUri: BaseUri  = BaseUri("http://localhost", Label.unsafe("v1"))
  implicit private val uuidF: UUIDF      = UUIDF.fixed(UUID.randomUUID())
  implicit private val subject: Subject  = Anonymous
  implicit private val externalIdxConfig = externalIndexing
  implicit private val aggCfg            = TermAggregationsConfig(100, 300)

  private val org           = Label.unsafe("org")
  private val project       = ProjectGen.project("org", "project", uuid = UUID.randomUUID(), orgUuid = UUID.randomUUID())
  private val (_, projects) = ProjectSetup.init(org :: Nil, project :: Nil).accepted

  private lazy val endpoint                  = docker.esHostConfig.endpoint
  private lazy val client                    = new ElasticSearchClient(HttpClient(), endpoint, 2000)
  private var graphAnalytics: GraphAnalytics = null

  "GraphAnalytics" should {

    "initialize" in {
      graphAnalytics = GraphAnalytics(client, projects).accepted
      val idx    = GraphAnalytics.idx(project.ref)
      client.createIndex(idx, Some(jsonObjectContentOf("elasticsearch/mappings.json")), None).accepted
      val robert = iri"http://localhost/Robert"
      val sam    = iri"http://localhost/Sam"
      val fred   = iri"http://localhost/fred"
      val anna   = iri"http://localhost/Anna"
      client
        .bulk(
          List(
            ElasticSearchBulk.Index(idx, "1", jsonContentOf("document-source.json", "id" -> sam, "brother" -> sam)),
            ElasticSearchBulk.Index(idx, "2", jsonContentOf("document-source.json", "id" -> anna, "brother" -> robert)),
            ElasticSearchBulk.Index(idx, "3", jsonContentOf("document-source.json", "id" -> sam, "brother" -> fred))
          )
        )
        .accepted
    }

    "fetch relationships" in eventually {
      graphAnalytics.relationships(project.ref).accepted shouldEqual
        AnalyticsGraph(
          List(Node(schema.Person, "Person", 3)),
          List(Edge(schema.Person, schema.Person, 3, Vector(EdgePath(schema + "brother", "brother"))))
        )
    }

    "fetch properties" in {
      graphAnalytics.properties(project.ref, schema.Person).accepted shouldEqual
        PropertiesStatistics(
          Metadata(schema.Person, "Person", 3),
          List(
            PropertiesStatistics(Metadata(schema + "givenName", "givenName", 3), List.empty),
            PropertiesStatistics(Metadata(schema + "brother", "brother", 3), List.empty),
            PropertiesStatistics(
              Metadata(schema + "address", "address", 3),
              List(PropertiesStatistics(Metadata(schema + "street", "street", 3), List.empty))
            )
          )
        )

    }
  }
}
