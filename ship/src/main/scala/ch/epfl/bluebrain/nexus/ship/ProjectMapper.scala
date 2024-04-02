package ch.epfl.bluebrain.nexus.ship

import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.ship.config.ShipConfig.ProjectMapping

trait ProjectMapper {
  def map(project: ProjectRef): ProjectRef
}

object ProjectMapper {

  def apply(projectMapping: Option[ProjectMapping]): ProjectMapper =
    (project: ProjectRef) =>
      projectMapping match {
        case Some(mapping) => mapping.getOrElse(project, project)
        case None          => project
      }

}
