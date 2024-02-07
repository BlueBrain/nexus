package ch.epfl.bluebrain.nexus.delta.sdk.typehierarchy

import ch.epfl.bluebrain.nexus.delta.sourcing.config.EventLogConfig
import pureconfig.ConfigReader
import pureconfig.generic.semiauto.deriveReader

final case class TypeHierarchyConfig(eventLog: EventLogConfig)

object TypeHierarchyConfig {
  implicit final val orgsConfigReader: ConfigReader[TypeHierarchyConfig] =
    deriveReader[TypeHierarchyConfig]
}
