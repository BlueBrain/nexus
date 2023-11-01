package ch.epfl.bluebrain.nexus.delta.sdk.deletion

import cats.effect.IO
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.sdk.deletion.model.ProjectDeletionReport
import ch.epfl.bluebrain.nexus.delta.sourcing.{EntityDependencyStore, PartitionInit, Transactors}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sourcing.implicits._
import doobie.util.fragment.Fragment
import doobie.ConnectionIO
import doobie.implicits._
import io.circe.syntax.EncoderOps

final private[deletion] class ProjectDeletionStore(xas: Transactors) {

  /**
    * Delete the project partitions and save the report
    */
  def deleteAndSaveReport(report: ProjectDeletionReport): IO[Unit] =
    (
      deleteProject(report.project) >>
        EntityDependencyStore.deleteAll(report.project) >>
        saveReport(report)
    ).transact(xas.writeCE)

  /**
    * Delete partitions of the projects in events and states
    */
  private def deleteProject(project: ProjectRef): ConnectionIO[Unit] =
    List("scoped_events", "scoped_states").traverse { s =>
      Fragment.const(s"""DROP TABLE IF EXISTS ${PartitionInit.projectRefPartition(s, project)}""").update.run
    }.void

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
      .transact(xas.readCE)

}
