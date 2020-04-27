package ch.epfl.bluebrain.nexus.cli.config

import pureconfig.ConfigConvert
import pureconfig.generic.semiauto.deriveConvert

/**
  * The HTTP Client configuration.
  *
  * @param retry the retry strategy (policy and condition)
  */
final case class ClientConfig(retry: RetryStrategyConfig)

object ClientConfig {
  implicit final val clientConfigConvert: ConfigConvert[ClientConfig] =
    deriveConvert[ClientConfig]
}
