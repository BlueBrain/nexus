package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch

import akka.actor.ActorSystem
import akka.http.scaladsl.model.headers.BasicHttpCredentials
import cats.effect.Resource
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.ElasticSearchClient
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClientSetup
import ch.epfl.bluebrain.nexus.testkit.CirceLiteral
import ch.epfl.bluebrain.nexus.testkit.bio.ResourceFixture.TaskFixture
import ch.epfl.bluebrain.nexus.testkit.bio.{BioSuite, ResourceFixture}
import ch.epfl.bluebrain.nexus.testkit.elasticsearch.ElasticSearchContainer
import monix.bio.Task
import monix.execution.Scheduler

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

  private def resource(
      elasticsearch: Resource[Task, ElasticSearchContainer]
  )(implicit s: Scheduler): Resource[Task, ElasticSearchClient] = {
    for {
      (httpClient, actorSystem) <- HttpClientSetup()
      container                 <- elasticsearch
    } yield {
      implicit val as: ActorSystem                           = actorSystem
      implicit val credentials: Option[BasicHttpCredentials] = ElasticSearchContainer.credentials
      new ElasticSearchClient(httpClient, s"http://${container.getHost}:${container.getMappedPort(9200)}", 2000)
    }
  }.evalTap { client =>
    client.createIndexTemplate("test_template", template)
  }

  def suiteLocalFixture(name: String)(implicit s: Scheduler): TaskFixture[ElasticSearchClient] =
    ResourceFixture.suiteLocal(name, resource(ElasticSearchContainer.resource()))

  trait Fixture { self: BioSuite =>
    val esClient: ResourceFixture.TaskFixture[ElasticSearchClient] =
      ElasticSearchClientSetup.suiteLocalFixture("esclient")
  }

}
