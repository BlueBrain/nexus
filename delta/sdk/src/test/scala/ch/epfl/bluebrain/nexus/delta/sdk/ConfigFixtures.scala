package ch.epfl.bluebrain.nexus.delta.sdk

import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategyConfig
import ch.epfl.bluebrain.nexus.delta.sdk.cache.CacheConfig
import ch.epfl.bluebrain.nexus.delta.sdk.http.{HttpClientConfig, HttpClientWorthRetry}
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.PaginationConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.config.{EventLogConfig, QueryConfig}
import ch.epfl.bluebrain.nexus.delta.sourcing.query.RefreshStrategy
import org.scalatest.OptionValues

import scala.concurrent.duration._

trait ConfigFixtures extends OptionValues {

  def cacheConfig = CacheConfig(10, 5.minutes)

  def eventLogConfig = EventLogConfig(QueryConfig(5, RefreshStrategy.Stop), 100.millis)

  def pagination: PaginationConfig =
    PaginationConfig(
      defaultSize = 30,
      sizeLimit = 100,
      fromLimit = 10000
    )

  def httpClientConfig: HttpClientConfig =
    HttpClientConfig(RetryStrategyConfig.AlwaysGiveUp, HttpClientWorthRetry.never, true)

}
