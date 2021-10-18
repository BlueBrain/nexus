package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.transformation

import com.typesafe.config.Config

/**
  * Definition of a transformation to include in a view
  * @param name
  *   the identifier of the transformation to apply
  * @param description
  *   a description of what is expected from the transformation
  * @param config
  *   the configuration to parse a context for the transformation
  */
final case class TransformationDefinition(name: String, description: Option[String], config: Config)
