package ch.epfl.bluebrain.nexus.delta.sourcing.config

import pureconfig.ConfigReader
import pureconfig.generic.semiauto.deriveReader

import scala.concurrent.duration.FiniteDuration

/**
  * Configuration for saving the progress of projections
  *
  * @param maxNumberOfEntries
  *   the maximum number of entries in a stream to be processed before saving its progress
  * @param maxTimeWindow
  *   the maximum time allowed to pass between saving the progress
  */
final case class SaveProgressConfig(maxNumberOfEntries: Int, maxTimeWindow: FiniteDuration)

object SaveProgressConfig {
  implicit final val persistProgressConfigReader: ConfigReader[SaveProgressConfig] =
    deriveReader[SaveProgressConfig]
}
