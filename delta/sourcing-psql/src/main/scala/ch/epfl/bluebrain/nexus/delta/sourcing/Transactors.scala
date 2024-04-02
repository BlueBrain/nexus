package ch.epfl.bluebrain.nexus.delta.sourcing

import cats.effect.{IO, Resource}
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.cache.{CacheConfig, LocalCache}
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClasspathResourceLoader
import ch.epfl.bluebrain.nexus.delta.kernel.{Logger, Secret}
import ch.epfl.bluebrain.nexus.delta.sourcing.Transactors.{logger, PartitionsCache}
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

  private val loader = ClasspathResourceLoader()

  private def execDDL(ddl: String): IO[Unit] =
    loader
      .contentOf(ddl)
      .flatMap(Fragment.const0(_).update.run.transact(write))
      .onError { e =>
        logger.error(e)(s"Executing ddl $ddl failed.")
      }
      .void

  def execDDLs(ddls: List[String]): IO[Unit] =
    ddls.traverse(execDDL).void

}

object Transactors {

  private val logger = Logger[Transactors]

  private val dropScript      = "scripts/postgres/drop/drop-tables.ddl"
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
  def dropAndCreateDDLs: IO[List[String]] = ddls.map(dropScript :: _)

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
  def test(host: String, port: Int, username: String, password: String): Resource[IO, Transactors] = {
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
    init(databaseConfig)
  }

  def init(
      config: DatabaseConfig
  ): Resource[IO, Transactors] = {
    def transactor(access: DatabaseAccess, readOnly: Boolean, poolName: String): Resource[IO, HikariTransactor[IO]] = {
      for {
        ec         <- ExecutionContexts.fixedThreadPool[IO](access.poolSize)
        dataSource <- Resource.make[IO, HikariDataSource](IO.delay {
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
                      })(ds => IO.delay(ds.close()))
      } yield HikariTransactor[IO](dataSource, ec, None)
    }

    val transactors = for {
      read      <- transactor(config.read, readOnly = true, poolName = "ReadPool")
      write     <- transactor(config.write, readOnly = false, poolName = "WritePool")
      streaming <- transactor(config.streaming, readOnly = true, poolName = "StreamingPool")
      cache     <- Resource.eval(LocalCache.lru[String, Unit](config.cache))
    } yield Transactors(read, write, streaming, cache)

    transactors.evalTap { xas =>
      IO.whenA(config.tablesAutocreate) {
        ddls.flatMap(xas.execDDLs)
      }
    }
  }
}
