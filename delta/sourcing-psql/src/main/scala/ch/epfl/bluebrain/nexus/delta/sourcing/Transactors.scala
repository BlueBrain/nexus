package ch.epfl.bluebrain.nexus.delta.sourcing

import cats.effect.{Blocker, IO, Resource}
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.Secret
import ch.epfl.bluebrain.nexus.delta.kernel.cache.{CacheConfig, LocalCache}
import ch.epfl.bluebrain.nexus.delta.kernel.effect.migration._
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClasspathResourceUtils
import ch.epfl.bluebrain.nexus.delta.sourcing.Transactors.PartitionsCache
import ch.epfl.bluebrain.nexus.delta.sourcing.config.DatabaseConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.config.DatabaseConfig.DatabaseAccess
import com.zaxxer.hikari.HikariDataSource
import doobie.Fragment
import doobie.hikari.HikariTransactor
import doobie.implicits._
import doobie.util.ExecutionContexts
import doobie.util.transactor.Transactor
import io.github.classgraph.ClassGraph
import monix.bio.{IO => BIO, Task}
import monix.execution.Scheduler

import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

/**
  * Allow to define different transactors (and connection pools) for the different query purposes
  */
final case class Transactors(
    read: Transactor[Task],
    write: Transactor[Task],
    streaming: Transactor[Task],
    cache: PartitionsCache
)(implicit s: Scheduler) {

  def readCE: Transactor[IO]      = read.mapK(BIO.liftTo)
  def writeCE: Transactor[IO]     = write.mapK(BIO.liftTo)
  def streamingCE: Transactor[IO] = streaming.mapK(BIO.liftTo)

  def execDDL(ddl: String)(implicit cl: ClassLoader): Task[Unit] =
    ClasspathResourceUtils.bioContentOf(ddl).flatMap(Fragment.const0(_).update.run.transact(write)).void

  def execDDLs(ddls: List[String])(implicit cl: ClassLoader): Task[Unit] =
    ddls.traverse(execDDL).void

}

object Transactors {

  private val dropScript      = "/scripts/postgres/drop/drop-tables.ddl"
  private val scriptDirectory = "/scripts/postgres/init/"

  /**
    * Scans the available DDLs to create the schema
    */
  def ddls: Task[List[String]] = Task.delay {
    new ClassGraph()
      .acceptPaths(scriptDirectory)
      .scan()
      .getResourcesWithExtension("ddl")
      .getPaths
      .asScala
      .sorted
      .toList
  }

  /**
    * For testing purposes, drop the current tables and then executes the different available scripts
    */
  private[sourcing] def dropAndCreateDDLs: Task[List[String]] = ddls.map(dropScript :: _)

  /** Type of a cache that contains the hashed names of the projectRefs for which a partition was already created. */
  type PartitionsCache = LocalCache[String, Unit]

  /**
    * Create a test `Transactors` from the provided parameters
    * @param host
    *   the host
    * @param port
    *   the port
    * @param username
    *   the username
    * @param password
    *   the password
    */
  def test(host: String, port: Int, username: String, password: String)(implicit
      s: Scheduler
  ): Resource[Task, Transactors] = {
    val access         = DatabaseAccess(host, port, 10)
    val databaseConfig = DatabaseConfig(
      access,
      access,
      access,
      "unused",
      username,
      Secret(password),
      false,
      CacheConfig(500, 10.minutes)
    )
    init(databaseConfig)(getClass.getClassLoader, s)
  }

  def init(config: DatabaseConfig)(implicit classLoader: ClassLoader, s: Scheduler): Resource[Task, Transactors] = {
    def transactor(access: DatabaseAccess, readOnly: Boolean, poolName: String) = {
      for {
        ce        <- ExecutionContexts.fixedThreadPool[Task](access.poolSize)
        blocker   <- Blocker[Task]
        dataSource = {
          val ds = new HikariDataSource
          ds.setJdbcUrl(s"jdbc:postgresql://${access.host}:${access.port}/")
          ds.setUsername(config.username)
          ds.setPassword(config.password.value)
          ds.setDriverClassName("org.postgresql.Driver")
          ds.setMaximumPoolSize(access.poolSize)
          ds.setPoolName(poolName)
          ds.setAutoCommit(false)
          ds.setReadOnly(readOnly)
          ds
        }
      } yield HikariTransactor[Task](dataSource, ce, blocker)
    }

    for {
      read      <- transactor(config.read, readOnly = true, poolName = "ReadPool")
      write     <- transactor(config.write, readOnly = false, poolName = "WritePool")
      streaming <- transactor(config.streaming, readOnly = true, poolName = "StreamingPool")
      cache     <- Resource.eval(LocalCache.lru[String, Unit](config.cache).toUIO)
    } yield Transactors(read, write, streaming, cache)
  }.evalTap { xas =>
    Task.when(config.tablesAutocreate) {
      ddls.flatMap(xas.execDDLs)
    }
  }
}
