package ch.epfl.bluebrain.nexus.delta.sdk.acls

import ch.epfl.bluebrain.nexus.delta.sourcing.config.EventLogConfig
import pureconfig.ConfigReader
import pureconfig.generic.semiauto.deriveReader

/**
  * Configuration for the ACLs module
  *
  * @param eventLog
  *   The event log configuration
  */
final case class AclsConfig(
    eventLog: EventLogConfig
)

object AclsConfig {
  implicit final val aclsConfigReader: ConfigReader[AclsConfig] =
    deriveReader[AclsConfig]
}
