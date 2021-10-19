package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.pipe

import io.circe.JsonObject

/**
  * Definition of a pipe to include in a view
  * @param name
  *   the identifier of the pipe to apply
  * @param description
  *   a description of what is expected from the pipe
  * @param config
  *   the configuration to parse as a context for the pipe
  */
final case class PipeDef(name: String, description: Option[String], config: JsonObject)
