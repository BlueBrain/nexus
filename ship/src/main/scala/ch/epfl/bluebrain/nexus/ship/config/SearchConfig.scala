package ch.epfl.bluebrain.nexus.ship.config

import pureconfig.ConfigReader
import pureconfig.generic.semiauto.deriveReader

import scala.concurrent.duration.FiniteDuration

final case class SearchConfig(commit: String, rebuildInterval: FiniteDuration)

object SearchConfig {
  implicit val searchConfigReader: ConfigReader[SearchConfig] =
    deriveReader[SearchConfig]
}
