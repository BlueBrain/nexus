package ch.epfl.bluebrain.nexus.delta.sourcing.postgres

import cats.effect.{IO, Resource}
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.sourcing.partition.{DatabasePartitioner, PartitionStrategy}
import ch.epfl.bluebrain.nexus.delta.sourcing.{DDLLoader, Transactors}
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite
import ch.epfl.bluebrain.nexus.testkit.postgres.PostgresContainer
import doobie.Fragment
import doobie.syntax.all._
import doobie.postgres.sqlstate
import munit.Location
import munit.catseffect.IOFixture
import munit.catseffect.ResourceFixture.FixtureNotInstantiatedException
import org.postgresql.util.PSQLException

object Doobie {

  private val defaultPartitioningStrategy = PartitionStrategy.Hash(1)

  def resource(partitionStrategy: PartitionStrategy): Resource[IO, (DatabasePartitioner, Transactors)] = {
    val user     = PostgresUser
    val pass     = PostgresPassword
    val database = PostgresDb
    for {
      postgres    <- PostgresContainer.resource(user, pass, database)
      xas         <- Transactors.test(postgres.getHost, postgres.getMappedPort(5432), user, pass, database)
      _           <- Resource.eval(DDLLoader.dropAndCreateDDLs(partitionStrategy, xas))
      partitioner <- Resource.eval(DatabasePartitioner(partitionStrategy, xas))
    } yield (partitioner, xas)
  }

  def resourceDefault: Resource[IO, Transactors] = resource(defaultPartitioningStrategy).map(_._2)

  trait Fixture { self: NexusSuite =>

    val doobie: IOFixture[Transactors] = ResourceSuiteLocalFixture("doobie", resourceDefault)

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

      override def beforeAll(): IO[Unit] = resourceDefault.allocated.flatMap { value =>
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
        resourceDefault.evalMap { xas =>
          f(xas).map(xas -> _)
        }
      )

    def doobieInject[A, B](f1: Transactors => IO[A], f2: Transactors => IO[B]): IOFixture[(Transactors, A, B)] =
      ResourceSuiteLocalFixture(
        s"doobie",
        resourceDefault.evalMap { xas =>
          for {
            a <- f1(xas)
            b <- f2(xas)
          } yield (xas, a, b)
        }
      )
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
