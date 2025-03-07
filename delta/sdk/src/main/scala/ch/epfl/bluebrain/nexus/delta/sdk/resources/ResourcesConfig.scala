package ch.epfl.bluebrain.nexus.delta.sdk.resources

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.resources.ResourcesConfig.SchemaEnforcementConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.config.EventLogConfig
import pureconfig.ConfigReader
import pureconfig.generic.semiauto.deriveReader

/**
  * Configuration for the Resources module.
  *
  * @param eventLog
  *   configuration of the event log
  * @param schemaEnforcement
  *   configuration related to schema enforcement
  * @param skipUpdateNoChange
  *   do not create a new revision when the update does not introduce a change in the current resource state
  */
final case class ResourcesConfig(
    eventLog: EventLogConfig,
    schemaEnforcement: SchemaEnforcementConfig,
    skipUpdateNoChange: Boolean
)

object ResourcesConfig {

  /**
    * Configuration to allow to bypass schema enforcing in some cases
    * @param typeWhitelist
    *   types for which a schema is not required
    * @param allowNoTypes
    *   allow to skip schema validation for resources without any types
    */
  final case class SchemaEnforcementConfig(typeWhitelist: Set[Iri], allowNoTypes: Boolean)

  implicit final val resourcesConfigReader: ConfigReader[ResourcesConfig] = {
    implicit val schemaEnforcementReader: ConfigReader[SchemaEnforcementConfig] = deriveReader[SchemaEnforcementConfig]
    deriveReader[ResourcesConfig]
  }
}
