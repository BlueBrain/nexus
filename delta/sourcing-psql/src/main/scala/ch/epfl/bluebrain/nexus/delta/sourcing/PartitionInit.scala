package ch.epfl.bluebrain.nexus.delta.sourcing

import cats.effect.concurrent.Ref
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.sourcing.PartitionInit.{createOrgPartition, createProjectPartition, projectRefHash, PartitionsCache}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import doobie.Fragment
import doobie.free.connection
import monix.bio.Task

/**
  * Indicates the actions to take in order to initialize the partition of the scoped event/state tables. The main scoped
  * event/state table is partitioned by organization (cf. schema.ddl). Each organization partition is itself partitioned
  * by project.
  */
sealed trait PartitionInit {

  /**
    * Defines how the task that initializes the partition.
    * @param mainTable
    *   The name of the table that should be partitioned
    */
  def initializePartition(mainTable: String): doobie.ConnectionIO[Unit]

  /**
    * Defines how the [[PartitionsCache]] should be updated
    * @param cache
    *   Current cache to update
    */
  def updateCache(cache: PartitionsCache): Task[Unit]
}

/** Indicates that a partition should be created before inserting */
case class Execute(projectRef: ProjectRef) extends PartitionInit {
  override def initializePartition(mainTable: String): doobie.ConnectionIO[Unit] =
    (createOrgPartition(mainTable, projectRef) ++
      createProjectPartition(mainTable, projectRef)).update.run.void

  override def updateCache(cache: PartitionsCache): Task[Unit] =
    cache.update(_ + projectRefHash(projectRef))
}

/** Indicates that no partition action should be taken before inserting */
case object Noop extends PartitionInit {

  override def initializePartition(mainTable: String): doobie.ConnectionIO[Unit] =
    connection.unit

  override def updateCache(cache: PartitionsCache): Task[Unit] =
    Task.unit

}

object PartitionInit {

  type PartitionsCache = Ref[Task, Set[String]]

  /**
    * Constructs a PartitionInit based on the given project and provided cache. If the projectRef was already in the
    * cache, Noop is returned; otherwise Execute is returned.
    */
  def apply(projectRef: ProjectRef, cache: PartitionsCache): Task[PartitionInit] =
    cache.get.map { c =>
      if (c.contains(projectRefHash(projectRef))) Noop
      else Execute(projectRef)
    }

  /**
    * A query creating an org partition of the provided mainTable. The partition is itself partitioned by project.
    * @param mainTable
    *   The name of the main table we partition
    * @param projectRef
    *   The information used to name the partitions
    */
  def createOrgPartition(mainTable: String, projectRef: ProjectRef): Fragment =
    Fragment.const(s"""
         | CREATE TABLE IF NOT EXISTS ${orgPartition(mainTable, projectRef)}
         | PARTITION OF $mainTable FOR VALUES IN ('${projectRef.organization}')
         | PARTITION BY LIST (project);
         |""".stripMargin)

  /**
    * A query creating a project partition of the org partition (for the provided mainTable). The provided projectRef is
    * used to name the partitions.
    * @param mainTable
    *   The name of the main table we partition
    * @param projectRef
    *   The information used to name the partitions
    */
  def createProjectPartition(mainTable: String, projectRef: ProjectRef): Fragment =
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
