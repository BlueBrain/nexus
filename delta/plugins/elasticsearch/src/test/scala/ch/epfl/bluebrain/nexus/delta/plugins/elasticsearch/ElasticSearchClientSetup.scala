package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch

import cats.effect.{IO, Resource}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.ElasticSearchClient
import ch.epfl.bluebrain.nexus.testkit.CirceLiteral
import ch.epfl.bluebrain.nexus.testkit.elasticsearch.ElasticSearchContainer
import munit.CatsEffectSuite
import munit.catseffect.IOFixture
import org.http4s.Uri

object ElasticSearchClientSetup extends CirceLiteral {

  private val template = jobj"""{
                                 "index_patterns" : ["*"],
                                 "priority" : 1,
                                 "template": {
                                   "settings" : {
                                     "number_of_shards": 1,
                                     "number_of_replicas": 0,
                                     "refresh_interval": "10ms"
                                   }
                                 }
                               }"""

  def resource(): Resource[IO, ElasticSearchClient] =
    ElasticSearchContainer
      .resource()
      .flatMap { container =>
        val endpoint = Uri.unsafeFromString(s"http://${container.getHost}:${container.getMappedPort(9200)}")
        ElasticSearchClient(endpoint, ElasticSearchContainer.credentials, 2000)
      }
      .evalTap(_.createIndexTemplate("test_template", template))

  trait Fixture {
    self: CatsEffectSuite =>
    val esClient: IOFixture[ElasticSearchClient] = ResourceSuiteLocalFixture("esclient", resource())
  }
}
