package ch.epfl.bluebrain.nexus.delta.kernel.database

import cats.effect.{Blocker, Resource}
import ch.epfl.bluebrain.nexus.delta.kernel.Secret
import ch.epfl.bluebrain.nexus.delta.kernel.database.DatabaseConfig.DatabaseAccess
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClasspathResourceUtils
import com.zaxxer.hikari.HikariDataSource
import doobie.Fragment
import doobie.hikari.HikariTransactor
import doobie.implicits._
import doobie.util.ExecutionContexts
import doobie.util.transactor.Transactor
import monix.bio.Task

/**
  * Allow to define different transactors (and connection pools) for the different query purposes
  */
final case class Transactors(
    read: Transactor[Task],
    write: Transactor[Task],
    streaming: Transactor[Task]
) {

  def execDDL(filePath: String)(implicit cl: ClassLoader): Task[Unit] =
    ClasspathResourceUtils.ioContentOf(filePath).flatMap(Fragment.const0(_).update.run.transact(write)).void

}

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
  def test(host: String, port: Int, username: String, password: String): Resource[Task, Transactors] = {
    val access = DatabaseAccess(host, port, 10)
    init(DatabaseConfig(access, access, access, "unused", username, Secret(password), false))(getClass.getClassLoader)
  }

  def init(config: DatabaseConfig)(implicit classLoader: ClassLoader): Resource[Task, Transactors] = {
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
    } yield Transactors(read, write, streaming)
  }.evalTap { xas =>
    Task.when(config.tablesAutocreate)(xas.execDDL("/scripts/schema.ddl"))
  }

}
