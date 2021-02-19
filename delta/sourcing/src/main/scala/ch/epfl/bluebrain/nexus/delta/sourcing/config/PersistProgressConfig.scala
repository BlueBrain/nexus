package ch.epfl.bluebrain.nexus.delta.sourcing.config

import pureconfig.ConfigReader
import pureconfig.generic.semiauto.deriveReader

import scala.concurrent.duration.FiniteDuration

/**
  * Configuration for the persistence of progress of projections
  *
  * @param maxBatchSize the maximum number of items in a batch before persisting progress
  * @param maxTimeWindow the maximum time allowed to pass between persisting the progress
  */
final case class PersistProgressConfig(maxBatchSize: Int, maxTimeWindow: FiniteDuration)

object PersistProgressConfig {
  implicit final val persistProgressConfigReader: ConfigReader[PersistProgressConfig] =
    deriveReader[PersistProgressConfig]
}
