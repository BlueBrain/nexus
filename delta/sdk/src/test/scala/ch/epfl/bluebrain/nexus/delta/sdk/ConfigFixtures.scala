package ch.epfl.bluebrain.nexus.delta.sdk

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategyConfig
import ch.epfl.bluebrain.nexus.delta.kernel.cache.CacheConfig
import ch.epfl.bluebrain.nexus.delta.sdk.fusion.FusionConfig
import ch.epfl.bluebrain.nexus.delta.sdk.http.{HttpClientConfig, HttpClientWorthRetry}
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.PaginationConfig
import ch.epfl.bluebrain.nexus.delta.sdk.projects.ProjectsConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.config.{EventLogConfig, QueryConfig}
import ch.epfl.bluebrain.nexus.delta.sourcing.query.RefreshStrategy

import scala.concurrent.duration._

trait ConfigFixtures {

  def cacheConfig: CacheConfig = CacheConfig(10, 5.minutes)

  def queryConfig: QueryConfig = QueryConfig(5, RefreshStrategy.Stop)

  def eventLogConfig: EventLogConfig = EventLogConfig(queryConfig, 5.seconds)

  def pagination: PaginationConfig =
    PaginationConfig(
      defaultSize = 30,
      sizeLimit = 100,
      fromLimit = 10000
    )

  def httpClientConfig: HttpClientConfig =
    HttpClientConfig(RetryStrategyConfig.AlwaysGiveUp, HttpClientWorthRetry.never, true)

  def fusionConfig: FusionConfig = FusionConfig(Uri("https://bbp.epfl.ch/nexus/web/"), enableRedirects = true)

  def deletionConfig: ProjectsConfig.DeletionConfig = ProjectsConfig.DeletionConfig(
    enabled = true,
    1.second,
    RetryStrategyConfig.AlwaysGiveUp
  )
}
