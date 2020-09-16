package ch.epfl.bluebrain.nexus.delta.sdk.model.projects

import org.apache.jena.iri.IRI

/**
  * Type that represents a project payload for creation and update requests.
  *
  * @param description an optional description
  * @param apiMappings the API mappings
  * @param base        an optional base IRI for generated resource IDs
  * @param vocab       an optional vocabulary for resources with no context
  */
final case class ProjectFields(
    description: Option[String],
    apiMappings: Map[String, IRI],
    base: Option[IRI],
    vocab: Option[IRI]
)
