package ch.epfl.bluebrain.nexus.delta.kernel.database

import cats.effect.{Blocker, Resource}
import ch.epfl.bluebrain.nexus.delta.kernel.database.DatabaseConfig.DatabaseAccess
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClasspathResourceUtils
import com.zaxxer.hikari.HikariDataSource
import doobie.Fragment
import doobie.hikari.HikariTransactor
import doobie.implicits._
import doobie.util.ExecutionContexts
import doobie.util.transactor.Transactor
import monix.bio.Task
import monix.execution.Scheduler

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

  def shared(xa: Transactor[Task]): Transactors = Transactors(xa, xa, xa)

  /**
    * Create a shared `Transactors` from the provided parameters
    * @param host
    *   the host
    * @param port
    *   the port
    * @param username
    *   the username
    * @param password
    *   the password
    */
  def sharedFrom(host: String, port: Int, username: String, password: String)(implicit
      s: Scheduler
  ): Task[Transactors] =
    for {
      _  <- Task.delay(Class.forName("org.postgresql.Driver"))
      ds <- Task.delay {
              val ds = new HikariDataSource
              ds.setJdbcUrl(s"jdbc:postgresql://$host:$port/")
              ds.setUsername(username)
              ds.setPassword(password)
              ds.setDriverClassName("org.postgresql.Driver")
              ds
            }
      t  <- Task.delay {
              HikariTransactor[Task](ds, s, Blocker.liftExecutionContext(s))
            }
    } yield Transactors.shared(t)

  def init(config: DatabaseConfig)(implicit classLoader: ClassLoader): Resource[Task, Transactors] = {
    def transactor(access: DatabaseAccess, readOnly: Boolean) = {
      for {
        ce        <- ExecutionContexts.fixedThreadPool[Task](access.poolSize)
        blocker   <- Blocker[Task]
        dataSource = {
          val ds = new HikariDataSource
          ds.setJdbcUrl(s"jdbc:postgresql://${access.host}:${access.port}/")
          ds.setUsername(config.username)
          ds.setPassword(config.password.value)
          ds.setDriverClassName("org.postgresql.Driver")
          ds.setMaximumPoolSize(15)
          ds.setAutoCommit(false)
          ds.setReadOnly(readOnly)
          ds
        }
      } yield HikariTransactor[Task](dataSource, ce, blocker)
    }

    for {
      read      <- transactor(config.read, readOnly = true)
      write     <- transactor(config.write, readOnly = false)
      streaming <- transactor(config.streaming, readOnly = true)
    } yield Transactors(read, write, streaming)
  }.evalTap { xas =>
    Task.when(config.tablesAutocreate)(xas.execDDL("/scripts/schema.ddl"))
  }

}
