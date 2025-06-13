package ai.senscience.nexus.delta.plugins.graph.analytics

import ai.senscience.nexus.delta.plugins.graph.analytics.config.GraphAnalyticsConfig.TermAggregationsConfig
import ai.senscience.nexus.delta.plugins.graph.analytics.model.AnalyticsGraph.{Edge, EdgePath, Node}
import ai.senscience.nexus.delta.plugins.graph.analytics.model.PropertiesStatistics.Metadata
import ai.senscience.nexus.delta.plugins.graph.analytics.model.{AnalyticsGraph, PropertiesStatistics}
import cats.syntax.all.*
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.ElasticSearchAction
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.Refresh.WaitFor
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.{ElasticSearchClientSetup, NexusElasticsearchSuite}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.schema
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.iriStringContextSyntax
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ProjectGen
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContextDummy
import munit.AnyFixture

import java.util.UUID

class GraphAnalyticsSuite extends NexusElasticsearchSuite with ElasticSearchClientSetup.Fixture {

  override def munitFixtures: Seq[AnyFixture[?]] = List(esClient)

  private lazy val client = esClient()

  private val prefix = "test"

  private val project      = ProjectGen.project("org", "project", uuid = UUID.randomUUID(), orgUuid = UUID.randomUUID())
  private val fetchContext = FetchContextDummy(List(project))

  private lazy val graphAnalytics: GraphAnalytics =
    GraphAnalytics(client, fetchContext, "test", TermAggregationsConfig(100, 300))

  test("Initialize") {
    val index                           = GraphAnalytics.index(prefix, project.ref)
    val robert                          = iri"http://localhost/Robert"
    val sam                             = iri"http://localhost/Sam"
    val fred                            = iri"http://localhost/fred"
    val anna                            = iri"http://localhost/Anna"
    def fetchMapping                    = loader.jsonObjectContentOf("elasticsearch/mappings.json")
    def source(self: Iri, brother: Iri) =
      loader.jsonContentOf("document-source.json", "id" -> self, "brother" -> brother)

    for {
      mapping    <- fetchMapping
      _          <- client.createIndex(index, Some(mapping), None)
      operations <- List((sam, sam), (anna, robert), (sam, fred)).traverse { case (self, brother) =>
                      source(self, brother).map { document =>
                        ElasticSearchAction.Index(index, genString(), None, document)
                      }
                    }
      _          <- client.bulk(operations, WaitFor)
    } yield ()
  }

  test("Fetch relationships") {
    val expected = AnalyticsGraph(
      List(Node(schema.Person, "Person", 3)),
      List(Edge(schema.Person, schema.Person, 3, Vector(EdgePath(schema + "brother", "brother"))))
    )
    graphAnalytics.relationships(project.ref).assertEquals(expected)
  }

  test("Fetch properties") {
    val expected = PropertiesStatistics(
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
    graphAnalytics.properties(project.ref, schema.Person).assertEquals(expected)
  }
}
