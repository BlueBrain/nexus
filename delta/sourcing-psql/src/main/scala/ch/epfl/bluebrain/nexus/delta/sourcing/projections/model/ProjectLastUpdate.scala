package ch.epfl.bluebrain.nexus.delta.sourcing.projections.model

import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import doobie.postgres.implicits._
import doobie.Read

import java.time.Instant

final case class ProjectLastUpdate(project: ProjectRef, lastInstant: Instant, lastOrdering: Offset)

object ProjectLastUpdate {

  implicit val projectLastUpdateRead: Read[ProjectLastUpdate] =
    Read[(Label, Label, Instant, Offset)].map { case (org, project, instant, offset) =>
      ProjectLastUpdate(ProjectRef(org, project), instant, offset)
    }

}
