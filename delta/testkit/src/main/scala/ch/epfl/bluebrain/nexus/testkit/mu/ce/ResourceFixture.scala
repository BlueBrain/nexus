package ch.epfl.bluebrain.nexus.testkit.mu.ce

import cats.effect.{IO, Resource}
import munit.{AfterEach, AnyFixture, BeforeEach}

// adapted to monix-bio from https://github.com/typelevel/munit-cats-effect/blob/main/core/src/main/scala/munit/catseffect/ResourceFixture.scala
object ResourceFixture {

  final class FixtureNotInstantiatedException(name: String)
      extends Exception(
        s"The fixture `$name` was not instantiated. Override `munitFixtures` and include a reference to this fixture. A lazy initialisation may also be missing on one of the values relying on the requested resource."
      )

  def testLocal[A](name: String, resource: Resource[IO, A]): IOFixture[A] =
    new IOFixture[A](name) {
      @volatile var value: Option[(A, IO[Unit])] = None

      def apply(): A = value match {
        case Some((value, _)) => value
        case None             => throw new FixtureNotInstantiatedException(name)
      }

      override def beforeEach(context: BeforeEach): IO[Unit] =
        resource.allocated.flatMap { value =>
          IO(this.value = Some(value))
        }

      override def afterEach(context: AfterEach): IO[Unit] =
        value.fold(IO.unit) { case (_, release) => release }
    }

  def suiteLocal[A](name: String, resource: Resource[IO, A]): IOFixture[A] =
    new IOFixture[A](name) {
      @volatile var value: Option[(A, IO[Unit])] = None

      def apply(): A = value match {
        case Some((value, _)) => value
        case None             => throw new FixtureNotInstantiatedException(name)
      }

      override def beforeAll(): IO[Unit] =
        resource.allocated.flatMap { value =>
          IO(this.value = Some(value))
        }

      override def afterAll(): IO[Unit] =
        value.fold(IO.unit) { case (_, release) => release }
    }

  abstract class IOFixture[A](name: String) extends AnyFixture[A](name) {
    override def beforeAll(): IO[Unit]                     = IO.unit
    override def beforeEach(context: BeforeEach): IO[Unit] = IO.unit
    override def afterEach(context: AfterEach): IO[Unit]   = IO.unit
    override def afterAll(): IO[Unit]                      = IO.unit
  }
}
