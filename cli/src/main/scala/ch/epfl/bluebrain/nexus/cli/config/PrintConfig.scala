package ch.epfl.bluebrain.nexus.cli.config

import pureconfig.ConfigConvert
import pureconfig.generic.semiauto.deriveConvert

/**
  * Configuration for printing output to the client
  *
  * @param progressInterval the optional number of events between progresses being printed.
  */
final case class PrintConfig(progressInterval: Option[Int])

object PrintConfig {
  implicit final val printConfigConvert: ConfigConvert[PrintConfig] =
    deriveConvert[PrintConfig]
}
