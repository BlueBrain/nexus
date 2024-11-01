package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch

import akka.actor.ActorSystem
import akka.http.scaladsl.model.headers.BasicHttpCredentials
import cats.effect.{IO, Resource}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.ElasticSearchClient
import ch.epfl.bluebrain.nexus.testkit.CirceLiteral
import ch.epfl.bluebrain.nexus.testkit.elasticsearch.ElasticSearchContainer
import ch.epfl.bluebrain.nexus.testkit.http.HttpClientSetup
import munit.CatsEffectSuite
import munit.catseffect.IOFixture

object ElasticSearchClientSetup extends CirceLiteral with Fixtures {

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

  def resource(): Resource[IO, ElasticSearchClient] = {
    for {
      (httpClient, actorSystem) <- HttpClientSetup(compression = true)
      container                 <- ElasticSearchContainer.resource()
    } yield {
      implicit val as: ActorSystem                           = actorSystem
      implicit val credentials: Option[BasicHttpCredentials] = ElasticSearchContainer.credentials
      new ElasticSearchClient(
        httpClient,
        s"http://${container.getHost}:${container.getMappedPort(9200)}",
        2000,
        emptyResults
      )
    }
  }.evalTap { client =>
    client.createIndexTemplate("test_template", template)
  }

  trait Fixture {
    self: CatsEffectSuite =>
    val esClient: IOFixture[ElasticSearchClient] = ResourceSuiteLocalFixture("esclient", resource())
  }
}
