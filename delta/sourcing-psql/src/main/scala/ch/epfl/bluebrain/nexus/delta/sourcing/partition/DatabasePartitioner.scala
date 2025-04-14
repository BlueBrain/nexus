package ch.epfl.bluebrain.nexus.delta.sourcing.partition

import cats.data.NonEmptyList
import cats.effect.IO
import cats.syntax.all.*
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.sourcing.Transactors
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef}
import doobie.ConnectionIO
import doobie.syntax.all.*
import doobie.util.fragment.Fragment

/**
  * Allows to initialize, provision and delete projects and orgs according to the underlying partition strategy
  */
trait DatabasePartitioner {

  def onInit: IO[Unit]

  def onCreateProject(project: ProjectRef): IO[Unit]

  def onDeleteProject(project: ProjectRef): ConnectionIO[Unit]

  def onDeleteOrg(org: Label): IO[Unit]

}

object DatabasePartitioner {

  private val logger = Logger[DatabasePartitioner]

  final case class DifferentPartitionStrategyDetected(existing: PartitionStrategy, newValue: PartitionStrategy)
      extends Exception {
    override def fillInStackTrace(): DifferentPartitionStrategyDetected = this

    override def getMessage: String =
      s"The provided configuration ('$newValue') attempts to override the existing one ('$existing')."
  }

  private def partitionedTables = NonEmptyList.of("scoped_events", "scoped_states")

  private val hashPartitionFormat = "%04d"

  private[partition] def getConfig(value: PartitionStrategy, xas: Transactors) =
    sql"""SELECT strategy from partition_config""".query[PartitionStrategy].option.transact(xas.read).flatMap {
      case Some(existing) if existing != value =>
        IO.raiseError(DifferentPartitionStrategyDetected(existing, value))
      case Some(existing)                      =>
        logger.info(s"The partition strategy is set to '$existing'").as(true)
      case None                                =>
        logger.info(s"The partition strategy has not been set yet, initializing...").as(false)
    }

  private def saveConfig(value: PartitionStrategy) =
    sql"""INSERT INTO partition_config (strategy) values ($value)""".update.run

  /**
    * The partitioner implementation for the PostgreSQL hash strategy when a number of partitions are created at the
    * first startup and where a project lives in a single partition and shares it with other projects.
    */
  final private class HashPartitioner(value: PartitionStrategy.Hash, xas: Transactors) extends DatabasePartitioner {

    override def onInit: IO[Unit] = getConfig(value, xas).flatMap { exists =>
      IO.unlessA(exists) {
        val partitionIndices = NonEmptyList.fromListUnsafe((0 until value.modulo).toList)
        val partitionsInit   = partitionedTables
          .reduceMap { mainTable =>
            partitionIndices.foldMap { remainder => createPartition(mainTable, remainder) }
          }
          .update
          .run
        (partitionsInit >> saveConfig(value)).transact(xas.write).void
      }
    }

    private def createPartition(mainTable: String, remainder: Int) = {
      val partition = s"${mainTable}_${hashPartitionFormat.format(remainder)}"
      Fragment.const(s"""| CREATE TABLE IF NOT EXISTS $partition
                         | PARTITION OF $mainTable FOR VALUES WITH (MODULUS ${value.modulo}, REMAINDER $remainder) ;
                         |""".stripMargin)
    }

    override def onCreateProject(project: ProjectRef): IO[Unit] = IO.unit

    override def onDeleteProject(project: ProjectRef): ConnectionIO[Unit] =
      partitionedTables
        .reduceMap { mainTable =>
          Fragment.const(
            s"""DELETE FROM $mainTable WHERE org = '${project.organization}' and project = '${project.project}' ;"""
          )
        }
        .update
        .run
        .void

    override def onDeleteOrg(org: Label): IO[Unit] = IO.unit
  }

  /**
    * The partitioner implementation for the PostgreSQL list strategy where projects have their own partition. The
    * underlying partition is created at project creation
    */
  final private class ListPartitioner(xas: Transactors) extends DatabasePartitioner {

    override def onInit: IO[Unit] = getConfig(PartitionStrategy.List, xas).flatMap { exists =>
      IO.unlessA(exists)(saveConfig(PartitionStrategy.List).transact(xas.write).void)
    }

    override def onCreateProject(project: ProjectRef): IO[Unit] =
      partitionedTables
        .traverse { table =>
          createOrgPartition(table, project).update.run >>
            createProjectPartition(table, project).update.run
        }
        .void
        .transact(xas.write)

    override def onDeleteProject(project: ProjectRef): ConnectionIO[Unit] =
      partitionedTables.traverse { table =>
        Fragment.const(s"""DROP TABLE IF EXISTS ${projectPartition(table, project)}""").update.run
      }.void

    override def onDeleteOrg(org: Label): IO[Unit] =
      partitionedTables
        .traverse { table =>
          Fragment.const(s"DROP TABLE IF EXISTS ${orgPartition(table, org)}").update.run
        }
        .void
        .transact(xas.write)

    private def createOrgPartition(mainTable: String, project: ProjectRef): Fragment =
      Fragment.const(s"""| CREATE TABLE IF NOT EXISTS ${orgPartition(mainTable, project.organization)}
                         | PARTITION OF $mainTable FOR VALUES IN ('${project.organization}')
                         | PARTITION BY LIST (project);
                         |""".stripMargin)

    private def createProjectPartition(mainTable: String, project: ProjectRef): Fragment = {
      val orgPart = orgPartition(mainTable, project.organization)
      Fragment.const(s"""| CREATE TABLE IF NOT EXISTS ${projectPartition(mainTable, project)}
                         | PARTITION OF $orgPart
                         | FOR VALUES IN ('${project.project}')
                         |""".stripMargin)
    }

    private def projectPartition(mainTable: String, projectRef: ProjectRef) =
      s"${mainTable}_${ProjectRef.hash(projectRef)}"

    private def orgPartition(mainTable: String, orgId: Label) = s"${mainTable}_${Label.hash(orgId)}"
  }

  def apply(strategy: PartitionStrategy, xas: Transactors): IO[DatabasePartitioner] = {
    val partitioner = strategy match {
      case PartitionStrategy.List       => new ListPartitioner(xas)
      case hash: PartitionStrategy.Hash => new HashPartitioner(hash, xas)
    }
    partitioner.onInit.as(partitioner)
  }

}
