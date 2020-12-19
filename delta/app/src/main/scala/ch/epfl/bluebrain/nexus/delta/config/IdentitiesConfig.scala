package ch.epfl.bluebrain.nexus.delta.config

import ch.epfl.bluebrain.nexus.delta.service.identity.GroupsConfig
import pureconfig.ConfigReader
import pureconfig.generic.semiauto.deriveReader

/**
  * The identities module config.
  *
  * @param groups the groups configuration
  */
final case class IdentitiesConfig(
    groups: GroupsConfig
)

object IdentitiesConfig {

  implicit final val identitiesConfigReader: ConfigReader[IdentitiesConfig] =
    deriveReader[IdentitiesConfig]

}
