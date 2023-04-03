package ch.epfl.bluebrain.nexus.delta.sourcing

import cats.effect.concurrent.Ref
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.sourcing.PartitionInit.{projectRefHash, PartitionsCache}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import doobie.Fragment
import monix.bio.Task

sealed trait PartitionInit {
  def updateCache(cache: PartitionsCache): Task[Unit]
}

/** Indicates that a partition should be created before inserting */
case class Execute(projectRef: ProjectRef) extends PartitionInit {
  override def updateCache(cache: PartitionsCache): Task[Unit] =
    cache.update(_ + projectRefHash(projectRef))
}

/** Indicates that no partition action should be taken before inserting */
case class Noop() extends PartitionInit {
  override def updateCache(cache: PartitionsCache): Task[Unit] =
    Task.unit
}

object PartitionInit {

  type PartitionsCache = Ref[Task, Set[String]]

  def apply(projectRef: ProjectRef, cache: PartitionsCache): Task[PartitionInit] =
    cache.get.map { c =>
      if (c.contains(projectRefHash(projectRef))) Noop()
      else Execute(projectRef)
    }

  def createPartitions(mainTable: String, projectRef: ProjectRef): doobie.ConnectionIO[Unit] =
    (createOrgPartition(mainTable, projectRef) ++
      createProjectPartition(mainTable, projectRef)).update.run.void

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

  def projectRefHash(projectRef: ProjectRef): String =
    MD5.hash(projectRef.toString)

  private def projectRefPartition(mainTable: String, projectRef: ProjectRef) =
    s"${mainTable}_${projectRefHash(projectRef)}"

  private def orgHash(projectRef: ProjectRef) =
    MD5.hash(projectRef.organization.value)

  private def orgPartition(mainTable: String, projectRef: ProjectRef) =
    s"${mainTable}_${orgHash(projectRef)}"

}
