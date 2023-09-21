package ch.epfl.bluebrain.nexus.delta.sourcing.postgres

import cats.effect.Resource
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sourcing.{Execute, Transactors}
import ch.epfl.bluebrain.nexus.testkit.NexusSuite
import ch.epfl.bluebrain.nexus.testkit.bio.ResourceFixture
import ch.epfl.bluebrain.nexus.testkit.bio.ResourceFixture.TaskFixture
import ch.epfl.bluebrain.nexus.testkit.postgres.PostgresContainer
import doobie.implicits._
import doobie.postgres.sqlstate
import monix.bio.{IO, Task, UIO}
import munit.Location
import org.postgresql.util.PSQLException
import monix.execution.Scheduler.Implicits.global

object Doobie {

  val PostgresUser     = "postgres"
  val PostgresPassword = "postgres"

  def apply(
      postgres: Resource[Task, PostgresContainer],
      user: String = PostgresUser,
      pass: String = PostgresPassword
  )(implicit cl: ClassLoader): Resource[Task, Transactors] = {
    postgres
      .flatMap(container => Transactors.test(container.getHost, container.getMappedPort(5432), user, pass))
      .evalTap(xas => Transactors.dropAndCreateDDLs.flatMap(xas.execDDLs))
  }

  def resource(
      user: String = PostgresUser,
      pass: String = PostgresPassword
  )(implicit cl: ClassLoader): Resource[Task, Transactors] =
    apply(PostgresContainer.resource(user, pass), user, pass)

  def suiteLocalFixture(
      name: String,
      user: String = PostgresUser,
      pass: String = PostgresPassword
  )(implicit cl: ClassLoader): TaskFixture[Transactors] =
    ResourceFixture.suiteLocal(name, resource(user, pass))

  trait Fixture { self: NexusSuite =>
    val doobie: ResourceFixture.TaskFixture[Transactors] = Doobie.suiteLocalFixture("doobie")

    /**
      * Init the partition in the events and states table for the given projects
      */
    def initPartitions(xas: Transactors, projects: ProjectRef*): Task[Unit] =
      projects
        .traverse { project =>
          val partitionInit = Execute(project)
          partitionInit.initializePartition("scoped_events") >> partitionInit.initializePartition("scoped_states")
        }
        .transact(xas.write)
        .void
  }

  trait Assertions { self: munit.Assertions =>
    implicit class DoobieAssertionsOps[E, A](io: IO[E, A])(implicit loc: Location) {
      def expectUniqueViolation: UIO[Unit] = io.attempt.map {
        case Left(p: PSQLException) if p.getSQLState == sqlstate.class23.UNIQUE_VIOLATION.value => ()
        case Left(p: PSQLException)                                                             =>
          fail(
            s"Wrong sql state caught, expected: '${sqlstate.class23.UNIQUE_VIOLATION.value}', actual: '${p.getSQLState}' "
          )
        case Left(err)                                                                          =>
          fail(s"Wrong raised error type caught, expected: 'PSQLException', actual: '${err.getClass.getName}'")
        case Right(a)                                                                           =>
          fail(s"Expected raising error, but returned successful response with value '$a'")
      }
    }
  }
}
