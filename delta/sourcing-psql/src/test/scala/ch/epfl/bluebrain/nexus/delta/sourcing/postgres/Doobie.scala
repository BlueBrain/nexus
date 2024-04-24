package ch.epfl.bluebrain.nexus.delta.sourcing.postgres

import cats.effect.{IO, Resource}
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sourcing.{Execute, Transactors}
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite
import ch.epfl.bluebrain.nexus.testkit.postgres.PostgresContainer
import doobie.Fragment
import doobie.implicits._
import doobie.postgres.sqlstate
import munit.Location
import munit.catseffect.IOFixture
import munit.catseffect.ResourceFixture.FixtureNotInstantiatedException
import org.postgresql.util.PSQLException

object Doobie {
  def resource(): Resource[IO, Transactors] = {
    val user     = PostgresUser
    val pass     = PostgresPassword
    val database = PostgresDb
    for {
      postgres    <- PostgresContainer.resource(user, pass, database)
      transactors <- Transactors.test(postgres.getHost, postgres.getMappedPort(5432), user, pass, database)
      _           <- Resource.eval(Transactors.dropAndCreateDDLs.flatMap(transactors.execDDLs))
    } yield transactors
  }

  trait Fixture { self: NexusSuite =>

    val doobie: IOFixture[Transactors] =
      ResourceSuiteLocalFixture("doobie", resource())

    /**
      * Truncate all tables after each test
      */
    val doobieTruncateAfterTest: IOFixture[Transactors] = new IOFixture[Transactors]("doobie") {
      @volatile var value: Option[(Transactors, IO[Unit])] = None

      def apply(): Transactors = value match {
        case Some(v) => v._1
        case None    => throw new FixtureNotInstantiatedException(fixtureName)
      }

      def xas: Transactors = apply()

      override def beforeAll(): IO[Unit] = resource().allocated.flatMap { value =>
        IO(this.value = Some(value))
      }

      override def afterAll(): IO[Unit] = value.fold(IO.unit)(_._2)

      override def afterEach(context: AfterEach): IO[Unit] =
        for {
          allTables <- sql"""SELECT table_name from information_schema.tables WHERE table_schema = 'public'"""
                         .query[String]
                         .to[List]
                         .transact(xas.read)
          _         <- allTables
                         .traverse { table => Fragment.const(s"""TRUNCATE $table""").update.run.transact(xas.write) }
                         .onError(IO.println)
        } yield ()
    }

    def doobieInject[A](f: Transactors => IO[A]): IOFixture[(Transactors, A)] =
      ResourceSuiteLocalFixture(
        s"doobie",
        resource().evalMap { xas =>
          f(xas).map(xas -> _)
        }
      )

    def doobieInject[A, B](f1: Transactors => IO[A], f2: Transactors => IO[B]): IOFixture[(Transactors, A, B)] =
      ResourceSuiteLocalFixture(
        s"doobie",
        resource().evalMap { xas =>
          for {
            a <- f1(xas)
            b <- f2(xas)
          } yield (xas, a, b)
        }
      )

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
