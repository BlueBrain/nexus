package ch.epfl.bluebrain.nexus.delta.sourcing.postgres

import cats.effect.{ContextShift, IO, Resource}
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sourcing.{Execute, Transactors}
import ch.epfl.bluebrain.nexus.testkit.ce.CatsRunContext
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite
import ch.epfl.bluebrain.nexus.testkit.mu.ce.ResourceFixture
import ch.epfl.bluebrain.nexus.testkit.mu.ce.ResourceFixture.IOFixture
import ch.epfl.bluebrain.nexus.testkit.postgres.PostgresContainer
import doobie.implicits._
import doobie.postgres.sqlstate
//import monix.bio.{IO => BIO, Task, UIO}
import munit.Location
import org.postgresql.util.PSQLException

object Doobie {

  val PostgresUser     = "postgres"
  val PostgresPassword = "postgres"

  def apply(
      postgres: Resource[IO, PostgresContainer],
      user: String = PostgresUser,
      pass: String = PostgresPassword
  )(implicit cl: ClassLoader, cs: ContextShift[IO]): Resource[IO, Transactors] = {
    postgres
      .flatMap(container => Transactors.test(container.getHost, container.getMappedPort(5432), user, pass))
      .evalTap(xas => Transactors.dropAndCreateDDLs.flatMap(xas.execDDLs))
  }

  def resource(
      user: String = PostgresUser,
      pass: String = PostgresPassword
  )(implicit cl: ClassLoader, cs: ContextShift[IO]): Resource[IO, Transactors] =
    apply(PostgresContainer.resource(user, pass), user, pass)

  def suiteLocalFixture(
      name: String,
      user: String = PostgresUser,
      pass: String = PostgresPassword
  )(implicit cl: ClassLoader,cs: ContextShift[IO]): IOFixture[Transactors] =
    ResourceFixture.suiteLocal(name, resource(user, pass))

  trait Fixture { self: NexusSuite with CatsRunContext =>
    val doobie: ResourceFixture.IOFixture[Transactors] = Doobie.suiteLocalFixture("doobie")

    /**
      * Init the partition in the events and states table for the given projects
      */
    def initPartitions(xas: Transactors, projects: ProjectRef*): IO[Unit] =
      projects
        .traverse { project =>
          val partitionInit = Execute(project)
          partitionInit.initializePartition("scoped_events") >> partitionInit.initializePartition("scoped_states")
        }
        .transact(xas.write)
        .void
  }

  trait Assertions { self: munit.Assertions =>
    implicit class DoobieCatsAssertionsOps[A](io: IO[A])(implicit loc: Location) {
      def expectUniqueViolation: IO[Unit] = io.attempt.map {
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
