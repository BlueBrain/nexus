package ch.epfl.bluebrain.nexus.delta.sdk.model.projects

import ch.epfl.bluebrain.nexus.delta.sdk.model.Label

/**
  * A project label along with its parent organization label.
  *
  * @param organization the parent organization label
  * @param project      the project label
  */
final case class ProjectRef(organization: Label, project: Label)
