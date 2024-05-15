package ch.epfl.bluebrain.nexus.ship.config

import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StoragesConfig
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
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
    disableResourceValidation: Boolean,
    resourceTypesToIgnore: Set[Iri]
)

object InputConfig {

  type ProjectMapping = Map[ProjectRef, ProjectRef]

  implicit val mapReader: ConfigReader[ProjectMapping] =
    genericMapReader(str =>
      ProjectRef.parse(str).leftMap(e => CannotConvert(str, classOf[ProjectRef].getSimpleName, e))
    )

  implicit private val iriConfigReader: ConfigReader[Iri] =
    ConfigReader.fromString(str => Iri(str).leftMap(err => CannotConvert(str, classOf[Iri].getSimpleName, err)))

  implicit final val runConfigReader: ConfigReader[InputConfig] = deriveReader[InputConfig]
}
