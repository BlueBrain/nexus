package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch

import akka.actor.ActorSystem
import akka.http.scaladsl.model.headers.BasicHttpCredentials
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategyConfig
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.ElasticSearchClient
import ch.epfl.bluebrain.nexus.delta.sdk.http.{HttpClient, HttpClientConfig, HttpClientWorthRetry}
import ch.epfl.bluebrain.nexus.testkit.elasticsearch.ElasticSearchDocker
import monix.execution.Scheduler

trait ElasticSearchClientSetup {

  def docker: ElasticSearchDocker
  implicit def system: ActorSystem
  implicit val sc: Scheduler                                     = Scheduler.global
  implicit private val httpConfig: HttpClientConfig              =
    HttpClientConfig(RetryStrategyConfig.AlwaysGiveUp, HttpClientWorthRetry.never, compression = true)
  implicit private val credentials: Option[BasicHttpCredentials] = ElasticSearchDocker.Credentials

  lazy val esClient = new ElasticSearchClient(HttpClient(), docker.esHostConfig.endpoint, 2000)

}
