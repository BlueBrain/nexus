package ch.epfl.bluebrain.nexus.delta.config

import ch.epfl.bluebrain.nexus.delta.service.config.AggregateConfig

/**
  * Configuration for the Schemas module.
  *
  * @param aggregate configuration of the underlying aggregate
  */
final case class SchemasConfig(aggregate: AggregateConfig)
