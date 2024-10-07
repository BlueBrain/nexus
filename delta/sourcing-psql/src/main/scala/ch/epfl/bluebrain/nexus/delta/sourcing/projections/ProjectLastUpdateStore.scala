package ch.epfl.bluebrain.nexus.delta.sourcing.projections

import cats.effect.IO
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.sourcing.Transactors
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.model.ProjectLastUpdate
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.model.ProjectLastUpdate.ProjectLastUpdateMap
import doobie.Fragments
import doobie.syntax.all._
import doobie.postgres.implicits._

import java.time.Instant

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

  /**
    * Fetch all updates from the database
    */
  def fetchAll: IO[ProjectLastUpdateMap]

  /**
    * Fetch updates older than the given instant
    */
  def fetchUpdates(after: Instant): IO[ProjectLastUpdateMap]

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
      sql"""INSERT INTO project_last_updates (org, project, last_instant, last_state_ordering)
           |VALUES (${p.project.organization}, ${p.project.project} ,${p.lastInstant}, ${p.lastOrdering})
           |ON CONFLICT (org, project)
           |DO UPDATE set
           |  last_instant = EXCLUDED.last_instant,
           |  last_state_ordering = EXCLUDED.last_state_ordering;
           |""".stripMargin.update.run

    override def fetchAll: IO[ProjectLastUpdateMap] = fetch(None)

    override def fetchUpdates(after: Instant): IO[ProjectLastUpdateMap] = fetch(Some(after))

    private def fetch(after: Option[Instant]) = {
      val afterFragment = after.map { a => fr"last_instant > $a" }
      sql"""SELECT * from project_last_updates ${Fragments.whereAndOpt(afterFragment)}"""
        .query[ProjectLastUpdate]
        .map { plu => plu.project -> plu }
        .toMap
        .transact(xas.read)
    }
  }

}
