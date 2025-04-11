package ch.epfl.bluebrain.nexus.delta.sourcing

import cats.effect.IO
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClasspathResourceLoader
import ch.epfl.bluebrain.nexus.delta.sourcing.partition.PartitionStrategy
import doobie.syntax.all._
import doobie.util.fragment.Fragment
import io.github.classgraph.ClassGraph

import scala.jdk.CollectionConverters._

object DDLLoader {

  private val logger = Logger[DDLLoader.type]

  private val dropScript      = "scripts/postgres/drop/drop-tables.ddl"
  private val scriptDirectory = "/scripts/postgres/init/"
  private val loader          = ClasspathResourceLoader()

  private def ddls(path: String): IO[List[String]] = IO.delay {
    new ClassGraph()
      .acceptPaths(path)
      .scan()
      .getResourcesWithExtension("ddl")
      .getPaths
      .asScala
      .toList
  }

  private def execDDL(ddl: String, xas: Transactors) =
    loader
      .contentOf(ddl)
      .flatMap(Fragment.const0(_).update.run.transact(xas.write))
      .onError { case e =>
        logger.error(e)(s"Executing ddl $ddl failed.")
      }
      .void

  private def partitionScriptDir(partitionStrategy: PartitionStrategy) =
    partitionStrategy match {
      case PartitionStrategy.List    => s"$scriptDirectory/list/"
      case PartitionStrategy.Hash(_) => s"$scriptDirectory/hash/"
    }

  private def allScripts(partitionStrategy: PartitionStrategy) =
    for {
      commonScripts    <- ddls(s"$scriptDirectory/common/")
      partitionScripts <- ddls(partitionScriptDir(partitionStrategy))
    } yield partitionScripts.sorted ++ commonScripts.sorted

  def setup(tablesAutocreate: Boolean, partitionStrategy: PartitionStrategy, xas: Transactors): IO[Unit] =
    IO.whenA(tablesAutocreate) {
      for {
        _          <- logger.warn("This feature is for development purposes, not for real deployments.")
        allScripts <- allScripts(partitionStrategy)
        _          <- allScripts.traverse(execDDL(_, xas)).void
      } yield ()
    }

  /**
    * For testing purposes, drop the current tables and then executes the different available scripts
    */
  def dropAndCreateDDLs(partitionStrategy: PartitionStrategy, xas: Transactors): IO[Unit] =
    allScripts(partitionStrategy)
      .map(dropScript :: _)
      .flatMap(_.traverse(execDDL(_, xas)))
      .void

}
