package ch.epfl.bluebrain.nexus.delta.plugins.storage

import akka.util.Timeout
import ch.epfl.bluebrain.nexus.delta.kernel.{CacheIndexingConfig, RetryStrategyConfig}
import ch.epfl.bluebrain.nexus.delta.sdk.cache.KeyValueStoreConfig
import ch.epfl.bluebrain.nexus.delta.sdk.http.{HttpClientConfig, HttpClientWorthRetry}
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.PaginationConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.config.AggregateConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.processor.{EventSourceProcessorConfig, StopStrategyConfig}
import ch.epfl.bluebrain.nexus.delta.sourcing.{config, SnapshotStrategyConfig}
import org.scalatest.OptionValues

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

//TODO: ported from service module, we might want to avoid this duplication
trait ConfigFixtures extends OptionValues {

  def neverStop     = StopStrategyConfig(None, None)
  def neverSnapShot = SnapshotStrategyConfig(None, None, None).value

  def aggregate(implicit ec: ExecutionContext): AggregateConfig =
    config.AggregateConfig(stopStrategy = neverStop, snapshotStrategy = neverSnapShot, processor = processor)

  def httpClientConfig: HttpClientConfig =
    HttpClientConfig(RetryStrategyConfig.AlwaysGiveUp, HttpClientWorthRetry.never)

  def processor(implicit ec: ExecutionContext): EventSourceProcessorConfig = EventSourceProcessorConfig(
    askTimeout = Timeout(5.seconds),
    evaluationMaxDuration = 3.second,
    evaluationExecutionContext = ec,
    stashSize = 100
  )

  def keyValueStore: KeyValueStoreConfig =
    KeyValueStoreConfig(
      askTimeout = 5.seconds,
      consistencyTimeout = 2.seconds,
      RetryStrategyConfig.AlwaysGiveUp
    )

  def indexing: CacheIndexingConfig = CacheIndexingConfig(1, RetryStrategyConfig.ConstantStrategyConfig(1.second, 10))

  def pagination: PaginationConfig =
    PaginationConfig(
      defaultSize = 30,
      sizeLimit = 100,
      fromLimit = 10000
    )
}
