package ch.epfl.bluebrain.nexus.delta.sourcing.config

import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategyConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.config.ProjectionConfig.ClusterConfig
import pureconfig.ConfigReader
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
  * @param query
  *   a configuration for how to interact with the underlying store
  */
final case class ProjectionConfig(cluster: ClusterConfig,
                                  batch: BatchConfig,
                                  retry: RetryStrategyConfig,
                                  supervisionCheckInterval: FiniteDuration,
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
  *   the index of the current node; it's used in conjunction with the clusterSize to determine if a projection needs to
  *   be executed on the current node
  */
  final case class ClusterConfig(size: Int,
                                 nodeIndex: Int)

  object ClusterConfig {
    implicit final val clusterConfigReader: ConfigReader[ClusterConfig] =
      deriveReader[ClusterConfig]
  }

}
