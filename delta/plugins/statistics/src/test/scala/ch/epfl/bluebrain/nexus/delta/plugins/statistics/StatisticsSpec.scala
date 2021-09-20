package ch.epfl.bluebrain.nexus.delta.plugins.statistics

import akka.actor.ActorSystem
import akka.testkit.TestKit
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategyConfig
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.{ElasticSearchBulk, ElasticSearchClient}
import ch.epfl.bluebrain.nexus.delta.plugins.statistics.model.PropertiesStatistics.Metadata
import ch.epfl.bluebrain.nexus.delta.plugins.statistics.model.StatisticsGraph.{Edge, EdgePath, Node}
import ch.epfl.bluebrain.nexus.delta.plugins.statistics.model.{PropertiesStatistics, StatisticsGraph}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.schema
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ProjectGen
import ch.epfl.bluebrain.nexus.delta.sdk.http.{HttpClient, HttpClientConfig, HttpClientWorthRetry}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.{Anonymous, Subject}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Label}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.{ConfigFixtures, ProjectSetup}
import ch.epfl.bluebrain.nexus.testkit.ElasticSearchDocker.elasticsearchHost
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
class StatisticsSpec
    extends TestKit(ActorSystem("StatisticsSpec"))
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

  private val org           = Label.unsafe("org")
  private val project       = ProjectGen.project("org", "project", uuid = UUID.randomUUID(), orgUuid = UUID.randomUUID())
  private val (_, projects) = ProjectSetup.init(org :: Nil, project :: Nil).accepted

  private val endpoint               = elasticsearchHost.endpoint
  private val client                 = new ElasticSearchClient(HttpClient(), endpoint, 2000)
  private var statistics: Statistics = null

  "Statistics" should {

    "initialize" in {
      statistics = Statistics(client, projects).accepted
      val idx    = Statistics.idx(project.ref)
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
      statistics.relationships(project.ref).accepted shouldEqual
        StatisticsGraph(
          List(Node(schema.Person, "Person", 3)),
          List(Edge(schema.Person, schema.Person, 3, Vector(EdgePath(schema + "brother", "brother"))))
        )
    }

    "fetch properties" in {
      statistics.properties(project.ref, schema.Person).accepted shouldEqual
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
