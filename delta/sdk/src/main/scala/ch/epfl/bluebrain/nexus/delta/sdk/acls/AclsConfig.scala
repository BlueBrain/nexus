package ch.epfl.bluebrain.nexus.delta.sdk.acls

import ch.epfl.bluebrain.nexus.delta.sourcing.config.EventLogConfig
import pureconfig.ConfigReader
import pureconfig.generic.semiauto.deriveReader

/**
  * Configuration for the ACLs module
  *
  * @param eventLog
  *   The event log configuration
  * @param provisioning
  *   The provisioning
  * @param enableOwnerPermissions
  *   Enable the creation of owner permissions when
  */
final case class AclsConfig(
    eventLog: EventLogConfig,
    provisioning: AclProvisioningConfig,
    enableOwnerPermissions: Boolean
)

object AclsConfig {
  implicit final val aclsConfigReader: ConfigReader[AclsConfig] =
    deriveReader[AclsConfig]
}
