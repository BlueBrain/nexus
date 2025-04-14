package ch.epfl.bluebrain.nexus.delta.sdk.deletion

import cats.effect.IO
import cats.syntax.all.*
import ch.epfl.bluebrain.nexus.delta.sdk.deletion.model.ProjectDeletionReport
import ch.epfl.bluebrain.nexus.delta.sourcing.{EntityDependencyStore, Transactors}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sourcing.implicits.*
import ch.epfl.bluebrain.nexus.delta.sourcing.partition.DatabasePartitioner
import doobie.ConnectionIO
import doobie.syntax.all.*
import io.circe.syntax.EncoderOps

final private[deletion] class ProjectDeletionStore(xas: Transactors, databasePartitioner: DatabasePartitioner) {

  /**
    * Delete the project partitions and save the report
    */
  def deleteAndSaveReport(report: ProjectDeletionReport): IO[Unit] = {
    databasePartitioner.onDeleteProject(report.project) >>
      EntityDependencyStore.deleteAll(report.project) >>
      saveReport(report)
  }.transact(xas.write)

  /**
    * Save the deletion report for the given project
    */
  private def saveReport(report: ProjectDeletionReport): ConnectionIO[Unit] =
    sql"""INSERT INTO deleted_project_reports (value) VALUES (${report.asJson})""".stripMargin.update.run.void

  /**
    * List reports for the given project
    */
  def list(project: ProjectRef): IO[List[ProjectDeletionReport]] =
    sql"""SELECT value FROM deleted_project_reports WHERE value->>'project' = $project"""
      .query[ProjectDeletionReport]
      .to[List]
      .transact(xas.read)

}
