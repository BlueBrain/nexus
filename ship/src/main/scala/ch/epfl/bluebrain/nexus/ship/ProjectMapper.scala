package ch.epfl.bluebrain.nexus.ship

import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.ship.config.InputConfig.ProjectMapping

trait ProjectMapper {
  def map(project: ProjectRef): ProjectRef
}

object ProjectMapper {

  def apply(projectMapping: ProjectMapping): ProjectMapper =
    (project: ProjectRef) =>
      projectMapping match {
        case m if m.isEmpty => project
        case mapping        => mapping.getOrElse(project, project)
      }

}
