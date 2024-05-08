package ch.epfl.bluebrain.nexus.ship.config

import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StoragesConfig
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, ServiceAccountConfig}
import ch.epfl.bluebrain.nexus.delta.sourcing.config.EventLogConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.ship.config.InputConfig.ProjectMapping
import pureconfig.ConfigReader
import pureconfig.configurable.genericMapReader
import pureconfig.error.CannotConvert
import pureconfig.generic.semiauto.deriveReader

final case class InputConfig(
    originalBaseUri: BaseUri,
    targetBaseUri: BaseUri,
    eventLog: EventLogConfig,
    organizations: OrganizationCreationConfig,
    projectMapping: ProjectMapping = Map.empty,
    viewDefaults: ViewDefaults,
    serviceAccount: ServiceAccountConfig,
    storages: StoragesConfig,
    files: FileProcessingConfig,
    disableResourceValidation: Boolean
)

object InputConfig {

  type ProjectMapping = Map[ProjectRef, ProjectRef]

  implicit val mapReader: ConfigReader[ProjectMapping] =
    genericMapReader(str =>
      ProjectRef.parse(str).leftMap(e => CannotConvert(str, classOf[ProjectRef].getSimpleName, e))
    )

  implicit final val runConfigReader: ConfigReader[InputConfig] = deriveReader[InputConfig]
}
