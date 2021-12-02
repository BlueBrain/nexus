package ch.epfl.bluebrain.nexus.delta.sdk.testkit

import akka.util.Timeout
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategyConfig.ConstantStrategyConfig
import ch.epfl.bluebrain.nexus.delta.kernel.{CacheIndexingConfig, RetryStrategyConfig}
import ch.epfl.bluebrain.nexus.delta.sdk.cache.KeyValueStoreConfig
import ch.epfl.bluebrain.nexus.delta.sdk.http.{HttpClientConfig, HttpClientWorthRetry}
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.PaginationConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.config.{AggregateConfig, ExternalIndexingConfig, SaveProgressConfig}
import ch.epfl.bluebrain.nexus.delta.sourcing.processor.{EventSourceProcessorConfig, StopStrategyConfig}
import ch.epfl.bluebrain.nexus.delta.sourcing.{config, SnapshotStrategyConfig}
import org.scalatest.OptionValues

import scala.concurrent.duration._

trait ConfigFixtures extends OptionValues {

  def neverStop     = StopStrategyConfig(None, None)
  def neverSnapShot = SnapshotStrategyConfig(None, None, None).value

  def aggregate: AggregateConfig =
    config.AggregateConfig(stopStrategy = neverStop, snapshotStrategy = neverSnapShot, processor = processor)

  def processor: EventSourceProcessorConfig = EventSourceProcessorConfig(
    askTimeout = Timeout(6.seconds),
    evaluationMaxDuration = 5.second,
    stashSize = 100,
    RetryStrategyConfig.AlwaysGiveUp
  )

  def keyValueStore: KeyValueStoreConfig =
    KeyValueStoreConfig(
      askTimeout = 5.seconds,
      writeLocal = true,
      consistencyTimeout = 2.seconds,
      RetryStrategyConfig.AlwaysGiveUp
    )

  def cacheIndexing: CacheIndexingConfig =
    CacheIndexingConfig(1, RetryStrategyConfig.ConstantStrategyConfig(1.second, 10))

  def externalIndexing: ExternalIndexingConfig =
    config.ExternalIndexingConfig("prefix", 2, 100.millis, ConstantStrategyConfig(1.second, 10), persist, persist)

  def persist: SaveProgressConfig = SaveProgressConfig(2, 20.millis)

  def pagination: PaginationConfig =
    PaginationConfig(
      defaultSize = 30,
      sizeLimit = 100,
      fromLimit = 10000
    )

  def httpClientConfig: HttpClientConfig =
    HttpClientConfig(RetryStrategyConfig.AlwaysGiveUp, HttpClientWorthRetry.never, true)

}
