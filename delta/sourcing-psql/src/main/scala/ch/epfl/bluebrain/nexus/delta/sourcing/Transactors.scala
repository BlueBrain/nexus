package ch.epfl.bluebrain.nexus.delta.sourcing

import cats.effect.{Blocker, ContextShift, IO, Resource}
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.Secret
import ch.epfl.bluebrain.nexus.delta.kernel.cache.{CacheConfig, LocalCache}
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

import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

/**
  * Allow to define different transactors (and connection pools) for the different query purposes
  */
final case class Transactors(
    read: Transactor[IO],
    write: Transactor[IO],
    streaming: Transactor[IO],
    cache: PartitionsCache
) {

  def readCE: Transactor[IO]      = read
  def writeCE: Transactor[IO]     = write
  def streamingCE: Transactor[IO] = streaming

  private def execDDL(ddl: String)(implicit cl: ClassLoader): IO[Unit] =
    ClasspathResourceUtils.ioContentOf(ddl).flatMap(Fragment.const0(_).update.run.transact(writeCE)).void

  def execDDLs(ddls: List[String])(implicit cl: ClassLoader): IO[Unit] =
    ddls.traverse(execDDL).void

}

object Transactors {

  private val dropScript      = "/scripts/postgres/drop/drop-tables.ddl"
  private val scriptDirectory = "/scripts/postgres/init/"

  /**
    * Scans the available DDLs to create the schema
    */
  def ddls: IO[List[String]] = IO.delay {
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
  private[sourcing] def dropAndCreateDDLs: IO[List[String]] = ddls.map(dropScript :: _)

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
      cs: ContextShift[IO]
  ): Resource[IO, Transactors] = {
    val access         = DatabaseAccess(host, port, 10)
    val databaseConfig = DatabaseConfig(
      access,
      access,
      access,
      "unused",
      username,
      Secret(password),
      tablesAutocreate = false,
      CacheConfig(500, 10.minutes)
    )
    init(databaseConfig)(getClass.getClassLoader, cs)
  }

  def init(config: DatabaseConfig)(implicit classLoader: ClassLoader, cs: ContextShift[IO]): Resource[IO, Transactors] = {
    def transactor(access: DatabaseAccess, readOnly: Boolean, poolName: String): Resource[IO, HikariTransactor[IO]] = {
      for {
        ce <- ExecutionContexts.fixedThreadPool[IO](access.poolSize)
        blocker <- Blocker[IO]
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
      } yield HikariTransactor[IO](dataSource, ce, blocker)
    }

    val transactors = for {
      read <- transactor(config.read, readOnly = true, poolName = "ReadPool")
      write <- transactor(config.write, readOnly = false, poolName = "WritePool")
      streaming <- transactor(config.streaming, readOnly = true, poolName = "StreamingPool")
      cache <- Resource.eval(LocalCache.lru[String, Unit](config.cache))
    } yield Transactors(read, write, streaming, cache)

    transactors.evalTap { xas =>
      IO.whenA(config.tablesAutocreate) {
        ddls.flatMap(xas.execDDLs)
      }
    }
  }
}
