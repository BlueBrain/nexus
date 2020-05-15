package ch.epfl.bluebrain.nexus.cli.config

import ch.epfl.bluebrain.nexus.cli.ClientRetryCondition
import pureconfig.ConfigConvert
import pureconfig.generic.semiauto.deriveConvert

/**
  * The HTTP Client configuration.
  *
  * @param retry the retry strategy (policy and condition)
  */
final case class ClientConfig(retry: RetryStrategyConfig[ClientRetryCondition])

object ClientConfig {
  implicit final val clientConfigConvert: ConfigConvert[ClientConfig] =
    deriveConvert[ClientConfig]
}
