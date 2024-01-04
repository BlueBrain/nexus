package ch.epfl.bluebrain.nexus.delta.sourcing.config

import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategyConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.config.ProjectionConfig.ClusterConfig
import pureconfig.ConfigReader
import pureconfig.error.FailureReason
import pureconfig.generic.semiauto.deriveReader

import scala.concurrent.duration.FiniteDuration

/**
  * The projection configuration.
  *
  * @param cluster
  *   a configuration defining the cluster the current node operates in
  * @param batch
  *   a configuration definition how often we must persist the progress and errors of projections
  * @param retry
  *   a configuration defining the retry policy to apply when a projection fails
  * @param supervisionCheckInterval
  *   the interval at which projections are checked
  * @param deleteExpiredEvery
  *   the interval at which we delete expired ephemeral states, projection restarts and errors
  * @param failedElemTtl
  *   the life span of projection errors in database
  * @param restartTtl
  *   the life span of projection restarts
  * @param query
  *   a configuration for how to interact with the underlying store
  */
final case class ProjectionConfig(
    cluster: ClusterConfig,
    batch: BatchConfig,
    retry: RetryStrategyConfig,
    supervisionCheckInterval: FiniteDuration,
    deleteExpiredEvery: FiniteDuration,
    failedElemTtl: FiniteDuration,
    restartTtl: FiniteDuration,
    query: QueryConfig
)

object ProjectionConfig {
  implicit final val projectionConfigReader: ConfigReader[ProjectionConfig] =
    deriveReader[ProjectionConfig]

  /**
    * The cluster configuration.
    *
    * @param size
    *   the size of the Delta cluster; it's used in conjunction with the nodeIndex to determine if a projection needs to
    *   be executed on the current node
    * @param nodeIndex
    *   the index of the current node; it's used in conjunction with the clusterSize to determine if a projection needs
    *   to be executed on the current node
    */
  final case class ClusterConfig(size: Int, nodeIndex: Int)

  object ClusterConfig {
    implicit final val clusterConfigReader: ConfigReader[ClusterConfig] =
      deriveReader[ClusterConfig].emap { config =>
        Either.cond(
          config.nodeIndex < config.size,
          config,
          new FailureReason {
            override def description: String = s"'node-index' must be smaller than 'size'"
          }
        )

      }
  }

}
