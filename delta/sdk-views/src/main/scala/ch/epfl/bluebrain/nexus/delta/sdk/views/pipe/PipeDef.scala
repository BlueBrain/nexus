package ch.epfl.bluebrain.nexus.delta.sdk.views.pipe

import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLd

/**
  * Definition of a pipe to include in a view
  * @param name
  *   the identifier of the pipe to apply
  * @param description
  *   a description of what is expected from the pipe
  * @param context
  *   the context to provide to the pipe
  */
final case class PipeDef(name: String, description: Option[String], context: Option[ExpandedJsonLd])
