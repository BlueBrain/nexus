package ch.epfl.bluebrain.nexus.ship.config

import ch.epfl.bluebrain.nexus.delta.sdk.Defaults
import pureconfig.ConfigReader
import pureconfig.generic.semiauto.deriveReader

case class ViewDefaults(
    elasticsearch: Defaults,
    blazegraph: Defaults,
    search: Defaults
)

object ViewDefaults {
  implicit val viewDefaultsConfigReader: ConfigReader[ViewDefaults] =
    deriveReader[ViewDefaults]
}
