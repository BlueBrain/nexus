package ch.epfl.bluebrain.nexus.delta.sourcing.config

import pureconfig.ConfigReader
import pureconfig.generic.semiauto.deriveReader

import scala.concurrent.duration.FiniteDuration

/**
  * The projection configuration.
  *
  * @param clusterSize
  *   the size of the Delta cluster; it's used in conjunction with the nodeIndex to determine if a projection needs to
  *   be executed on the current node
  * @param nodeIndex
  *   the index of the current node; it's used in conjunction with the clusterSize to determine if a projection needs to
  *   be executed on the current node
  * @param persistOffsetInterval
  *   the interval when projection offsets are persisted if there are changes
  * @param supervisionCheckInterval
  *   the interval at which projections are checked
  */
final case class ProjectionConfig(
    clusterSize: Int,
    nodeIndex: Int,
    persistOffsetInterval: FiniteDuration,
    supervisionCheckInterval: FiniteDuration
)

object ProjectionConfig {
  implicit final val projectionConfigReader: ConfigReader[ProjectionConfig] =
    deriveReader[ProjectionConfig]
}
