package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef

/**
  * A view reference.
  *
  * @param project the view parent project
  * @param viewId  the view id
  */
final case class ViewRef(project: ProjectRef, viewId: Iri)
