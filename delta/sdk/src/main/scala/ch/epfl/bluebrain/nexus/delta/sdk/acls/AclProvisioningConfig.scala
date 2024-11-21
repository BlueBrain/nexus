package ch.epfl.bluebrain.nexus.delta.sdk.acls

import pureconfig.ConfigReader
import pureconfig.generic.semiauto.deriveReader

import java.nio.file.Path

final case class AclProvisioningConfig(enabled: Boolean, path: Option[Path])

object AclProvisioningConfig {
  implicit final val aclProvisioningConfigReader: ConfigReader[AclProvisioningConfig] =
    deriveReader[AclProvisioningConfig]
}
