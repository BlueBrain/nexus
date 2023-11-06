package ch.epfl.bluebrain.nexus.delta.sdk.projects

import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategyConfig
import ch.epfl.bluebrain.nexus.delta.kernel.cache.CacheConfig
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.PaginationConfig
import ch.epfl.bluebrain.nexus.delta.sdk.projects.ProjectsConfig.DeletionConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.config.EventLogConfig
import pureconfig.ConfigReader
import pureconfig.generic.semiauto._

import scala.concurrent.duration.FiniteDuration

/**
  * Configuration for the Projects module.
  *
  * @param eventLog
  *   configuration of the event log
  * @param pagination
  *   configuration for how pagination should behave in listing operations
  * @param cache
  *   the cache configuration for the uuids cache
  * @param deletion
  *   the deletion configuration
  */
final case class ProjectsConfig(
    eventLog: EventLogConfig,
    pagination: PaginationConfig,
    cache: CacheConfig,
    deletion: DeletionConfig
)

object ProjectsConfig {

  /**
    * Configuration for project deletion
    * @param enabled
    *   if the project deletion is enabled
    * @param propagationDelay
    *   gives a delay for project deletion tasks to be taken into account, especially for views deprecation events to be
    *   acknowledged by coordinators
    * @param retryStrategy
    *   the retry strategy to apply when a project deletion fails
    */
  final case class DeletionConfig(
      enabled: Boolean,
      propagationDelay: FiniteDuration,
      retryStrategy: RetryStrategyConfig
  )

  object DeletionConfig {
    implicit final val deletionConfigReader: ConfigReader[DeletionConfig] =
      deriveReader[DeletionConfig]
  }

  implicit final val projectConfigReader: ConfigReader[ProjectsConfig] =
    deriveReader[ProjectsConfig]
}
