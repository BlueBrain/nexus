package ch.epfl.bluebrain.nexus.delta.sourcing.projections

import cats.effect.IO
import cats.syntax.all.*
import ch.epfl.bluebrain.nexus.delta.sourcing.Transactors
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.model.ProjectLastUpdate
import doobie.syntax.all.*
import doobie.postgres.implicits.*

/**
  * Keeps track of the last update on a given project
  */
trait ProjectLastUpdateStore {

  /**
    * * Delete the entry for the given project
    */
  def delete(project: ProjectRef): IO[Unit]

  /**
    * Inserts/updates a list of updates
    */
  def save(updates: List[ProjectLastUpdate]): IO[Unit]

}

object ProjectLastUpdateStore {

  def apply(xas: Transactors): ProjectLastUpdateStore = new ProjectLastUpdateStore {

    override def delete(project: ProjectRef): IO[Unit] =
      sql"""DELETE FROM project_last_updates WHERE org = ${project.organization} and project = ${project.project}""".update.run
        .transact(xas.write)
        .void

    override def save(updates: List[ProjectLastUpdate]): IO[Unit] =
      updates
        .traverse(saveOne)
        .transact(xas.write)
        .void

    private def saveOne(p: ProjectLastUpdate) =
      sql"""INSERT INTO project_last_updates (org, project, last_instant, last_ordering)
           |VALUES (${p.project.organization}, ${p.project.project} ,${p.lastInstant}, ${p.lastOrdering})
           |ON CONFLICT (org, project)
           |DO UPDATE set
           |  last_instant = EXCLUDED.last_instant,
           |  last_ordering = EXCLUDED.last_ordering;
           |""".stripMargin.update.run
  }

}
