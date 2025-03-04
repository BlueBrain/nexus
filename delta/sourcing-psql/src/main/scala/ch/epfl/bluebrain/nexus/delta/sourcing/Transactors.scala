package ch.epfl.bluebrain.nexus.delta.sourcing

import cats.effect.{IO, Resource}
import ch.epfl.bluebrain.nexus.delta.kernel.Secret
import ch.epfl.bluebrain.nexus.delta.sourcing.config.DatabaseConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.config.DatabaseConfig.DatabaseAccess
import ch.epfl.bluebrain.nexus.delta.sourcing.partition.PartitionStrategy
import com.zaxxer.hikari.HikariDataSource
import doobie.hikari.HikariTransactor
import doobie.util.ExecutionContexts
import doobie.util.transactor.Transactor

import scala.concurrent.duration._

/**
  * Allow to define different transactors (and connection pools) for the different query purposes
  */
final case class Transactors(
    read: Transactor[IO],
    write: Transactor[IO],
    streaming: Transactor[IO]
)

object Transactors {

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
  def test(host: String, port: Int, username: String, password: String, database: String): Resource[IO, Transactors] = {
    val access         = DatabaseAccess(host, port, 10)
    val databaseConfig = DatabaseConfig(
      access,
      access,
      access,
      PartitionStrategy.Hash(1),
      database,
      username,
      Secret(password),
      tablesAutocreate = false,
      rewriteBatchInserts = true,
      5.seconds
    )
    apply(databaseConfig)
  }

  def apply(config: DatabaseConfig): Resource[IO, Transactors] = {

    def jdbcUrl(access: DatabaseAccess, readOnly: Boolean) = {
      val baseUrl = s"jdbc:postgresql://${access.host}:${access.port}/${config.name}"
      if (!readOnly && config.rewriteBatchInserts)
        s"$baseUrl?reWriteBatchedInserts=true"
      else
        baseUrl
    }

    def transactor(access: DatabaseAccess, readOnly: Boolean, poolName: String): Resource[IO, HikariTransactor[IO]] = {
      for {
        ec         <- ExecutionContexts.fixedThreadPool[IO](access.poolSize)
        dataSource <- Resource.make[IO, HikariDataSource](IO.delay {
                        val ds = new HikariDataSource
                        ds.setJdbcUrl(jdbcUrl(access, readOnly))
                        ds.setUsername(config.username)
                        ds.setPassword(config.password.value)
                        ds.setDriverClassName("org.postgresql.Driver")
                        ds.setMaximumPoolSize(access.poolSize)
                        ds.setPoolName(poolName)
                        ds.setAutoCommit(false)
                        ds.setReadOnly(readOnly)
                        ds
                      })(ds => IO.delay(ds.close()))
      } yield HikariTransactor[IO](dataSource, ec, Some(QueryLogHandler(poolName, config.slowQueryThreshold)))
    }

    val transactors = for {
      read      <- transactor(config.read, readOnly = true, poolName = "ReadPool")
      write     <- transactor(config.write, readOnly = false, poolName = "WritePool")
      streaming <- transactor(config.streaming, readOnly = true, poolName = "StreamingPool")
    } yield Transactors(read, write, streaming)

    transactors.evalTap { xas =>
      DDLLoader.setup(config.tablesAutocreate, config.partitionStrategy, xas)
    }
  }
}
