package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch

import akka.actor.ActorSystem
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategyConfig
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.ElasticSearchClient
import ch.epfl.bluebrain.nexus.delta.sdk.http.{HttpClient, HttpClientConfig, HttpClientWorthRetry}
import ch.epfl.bluebrain.nexus.testkit.ElasticSearchDocker._
import monix.execution.Scheduler

trait ElasticSearchClientSetup {

  implicit def system: ActorSystem
  implicit val sc: Scheduler                        = Scheduler.global
  implicit private val httpConfig: HttpClientConfig =
    HttpClientConfig(RetryStrategyConfig.AlwaysGiveUp, HttpClientWorthRetry.never, compression = true)

  val esClient = new ElasticSearchClient(HttpClient(), elasticsearchHost.endpoint, 2000)

}
