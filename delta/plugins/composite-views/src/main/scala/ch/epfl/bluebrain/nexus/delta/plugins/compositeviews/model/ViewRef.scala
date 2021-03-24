package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef

/**
  * View reference.
  *
  * @param project  the project to which the view belongs
  * @param viewId   the view id
  */
final case class ViewRef(project: ProjectRef, viewId: Iri)
