package ch.epfl.bluebrain.nexus.testkit.postgres

import cats.effect.Resource
import cats.implicits.toTraverseOps
import ch.epfl.bluebrain.nexus.delta.kernel.database.Transactors
import ch.epfl.bluebrain.nexus.testkit.bio.ResourceFixture.TaskFixture
import ch.epfl.bluebrain.nexus.testkit.bio.{BioSuite, ResourceFixture}
import doobie.postgres.sqlstate
import monix.bio.{IO, Task, UIO}
import munit.Location
import org.postgresql.util.PSQLException

import java.nio.file.{Path, Paths}

object Doobie {

  val PostgresUser     = "postgres"
  val PostgresPassword = "postgres"
  val DDLs: List[Path] = List(Paths.get("/scripts/drop-tables.ddl"), Paths.get("/scripts/schema.ddl"))

  def apply(
      postgres: Resource[Task, PostgresContainer],
      user: String = PostgresUser,
      pass: String = PostgresPassword,
      ddls: List[Path] = DDLs
  )(implicit cl: ClassLoader): Resource[Task, Transactors] = {
    postgres
      .flatMap(container => Transactors.test(container.getHost, container.getMappedPort(5432), user, pass))
      .evalTap(xas => ddls.traverse(path => xas.execDDL(path.toString)))
  }

  def resource(
      user: String = PostgresUser,
      pass: String = PostgresPassword,
      ddls: List[Path] = DDLs
  )(implicit cl: ClassLoader): Resource[Task, Transactors] =
    apply(PostgresContainer.resource(user, pass), user, pass, ddls)

  def suiteLocalFixture(
      name: String,
      user: String = PostgresUser,
      pass: String = PostgresPassword,
      ddls: List[Path] = DDLs
  )(implicit cl: ClassLoader): TaskFixture[Transactors] =
    ResourceFixture.suiteLocal(name, resource(user, pass, ddls))

  trait Fixture { self: BioSuite =>
    val doobie: ResourceFixture.TaskFixture[Transactors] = Doobie.suiteLocalFixture("doobie")
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
