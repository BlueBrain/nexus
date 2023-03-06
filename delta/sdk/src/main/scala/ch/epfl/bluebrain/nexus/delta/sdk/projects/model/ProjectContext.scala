package ch.epfl.bluebrain.nexus.delta.sdk.projects.model

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri

/**
  * Defines the context applied within a project
  * @param apiMappings
  *   the API mappings
  * @param base
  *   the base Iri for generated resource IDs
  * @param vocab
  *   an optional vocabulary for resources with no context
  */
final case class ProjectContext(apiMappings: ApiMappings, base: ProjectBase, vocab: Iri)

object ProjectContext {

  def unsafe(apiMappings: ApiMappings, base: Iri, vocab: Iri): ProjectContext =
    ProjectContext(apiMappings, ProjectBase(base), vocab)
}
