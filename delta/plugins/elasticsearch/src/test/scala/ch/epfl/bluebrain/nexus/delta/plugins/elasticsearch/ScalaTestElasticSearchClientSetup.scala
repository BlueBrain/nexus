package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch

import akka.actor.ActorSystem
import akka.http.scaladsl.model.headers.BasicHttpCredentials
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategyConfig
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.ElasticSearchClient
import ch.epfl.bluebrain.nexus.delta.sdk.http.{HttpClient, HttpClientConfig, HttpClientWorthRetry}
import ch.epfl.bluebrain.nexus.testkit.CirceLiteral
import ch.epfl.bluebrain.nexus.testkit.elasticsearch.{ElasticSearchContainer, ElasticSearchDocker}
import monix.execution.Scheduler

trait ScalaTestElasticSearchClientSetup extends CirceLiteral {

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

  def docker: ElasticSearchDocker
  implicit def system: ActorSystem
  implicit val sc: Scheduler                                     = Scheduler.global
  implicit val httpConfig: HttpClientConfig                      =
    HttpClientConfig(RetryStrategyConfig.AlwaysGiveUp, HttpClientWorthRetry.never, compression = true)
  implicit private val credentials: Option[BasicHttpCredentials] = ElasticSearchContainer.Credentials

  lazy val esClient = {
    val c = new ElasticSearchClient(HttpClient(), docker.esHostConfig.endpoint, 2000)
    c.createIndexTemplate("test_template", template).runSyncUnsafe()
    c
  }

}
