package ch.epfl.bluebrain.nexus.delta.sdk.projects.model

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri

final case class ProjectContext(apiMappings: ApiMappings, base: ProjectBase, vocab: Iri)
