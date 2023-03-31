package ch.epfl.bluebrain.nexus.delta.sourcing

import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import doobie.Fragment

object Partition {

  private def orgHash(projectRef: ProjectRef) =
    MD5.hash(projectRef.organization.value)

  private def orgPartition(mainTable: String, projectRef: ProjectRef) =
    s"${mainTable}_${orgHash(projectRef)}"

  def projectRefHash(projectRef: ProjectRef): String =
    MD5.hash(projectRef.toString)

  private def projectRefPartition(mainTable: String, projectRef: ProjectRef) =
    s"${mainTable}_${projectRefHash(projectRef)}"

  private def createOrgPartition(mainTable: String, projectRef: ProjectRef) =
    Fragment.const(s"""
      | CREATE TABLE IF NOT EXISTS ${orgPartition(mainTable, projectRef)}
      | PARTITION OF $mainTable FOR VALUES IN ('${projectRef.organization}')
      | PARTITION BY LIST (project);
      |""".stripMargin)

  private def createProjectPartition(mainTable: String, projectRef: ProjectRef) =
    Fragment.const(s"""
      | CREATE TABLE IF NOT EXISTS ${projectRefPartition(mainTable, projectRef)}
      | PARTITION OF ${orgPartition(mainTable, projectRef)} FOR VALUES IN ('${projectRef.project}')
      |""".stripMargin)

  def createPartitions(mainTable: String, projectRef: ProjectRef): doobie.ConnectionIO[Unit] =
    (createOrgPartition(mainTable, projectRef) ++
      createProjectPartition(mainTable, projectRef)).update.run.void

}
