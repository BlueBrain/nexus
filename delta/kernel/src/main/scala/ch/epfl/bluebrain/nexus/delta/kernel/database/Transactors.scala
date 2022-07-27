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
    def transactor(access: DatabaseAccess) =
      for {
        ce      <- ExecutionContexts.fixedThreadPool[Task](access.poolSize)
        blocker <- Blocker[Task]
        xa      <- HikariTransactor.newHikariTransactor[Task](
                     "org.postgresql.Driver",
                     s"jdbc:postgresql://${access.host}:${access.port}/${config.name}?reWriteBatchedInserts=true&stringtype=unspecified",
                     config.username,
                     config.password.value,
                     ce,
                     blocker
                   )
      } yield xa

    for {
      read      <- transactor(config.read)
      write     <- transactor(config.write)
      streaming <- transactor(config.streaming)
    } yield Transactors(read, write, streaming)
  }.evalTap { xas =>
    Task.when(config.tablesAutocreate)(xas.execDDL("/scripts/schema.ddl"))
  }

}
