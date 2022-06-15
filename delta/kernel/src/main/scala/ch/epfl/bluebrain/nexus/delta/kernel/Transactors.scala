package ch.epfl.bluebrain.nexus.delta.kernel

import cats.effect.Blocker
import com.zaxxer.hikari.HikariDataSource
import doobie.hikari.HikariTransactor
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
)

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

}
