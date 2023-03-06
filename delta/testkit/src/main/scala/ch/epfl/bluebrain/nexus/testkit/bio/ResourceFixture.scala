package ch.epfl.bluebrain.nexus.testkit.bio

import cats.effect.Resource
import monix.bio.Task
import munit.{AfterEach, AnyFixture, BeforeEach}

// adapted to monix-bio from https://github.com/typelevel/munit-cats-effect/blob/main/core/src/main/scala/munit/catseffect/ResourceFixture.scala
object ResourceFixture {

  final class FixtureNotInstantiatedException(name: String)
      extends Exception(
        s"The fixture `$name` was not instantiated. Override `munitFixtures` and include a reference to this fixture. A lazy initialisation may also be missing on one of the values relying on the requested resource."
      )

  def testLocal[A](name: String, resource: Resource[Task, A]): TaskFixture[A] =
    new TaskFixture[A](name) {
      @volatile var value: Option[(A, Task[Unit])] = None

      def apply(): A = value match {
        case Some((value, _)) => value
        case None             => throw new FixtureNotInstantiatedException(name)
      }

      override def beforeEach(context: BeforeEach): Task[Unit] =
        resource.allocated.flatMap { value =>
          Task(this.value = Some(value))
        }

      override def afterEach(context: AfterEach): Task[Unit] =
        value.fold(Task.unit) { case (_, release) => release }
    }

  def suiteLocal[A](name: String, resource: Resource[Task, A]): TaskFixture[A] =
    new TaskFixture[A](name) {
      @volatile var value: Option[(A, Task[Unit])] = None

      def apply(): A = value match {
        case Some((value, _)) => value
        case None             => throw new FixtureNotInstantiatedException(name)
      }

      override def beforeAll(): Task[Unit] =
        resource.allocated.flatMap { value =>
          Task(this.value = Some(value))
        }

      override def afterAll(): Task[Unit] =
        value.fold(Task.unit) { case (_, release) => release }
    }

  abstract class TaskFixture[A](name: String) extends AnyFixture[A](name) {
    override def beforeAll(): Task[Unit]                     = Task.unit
    override def beforeEach(context: BeforeEach): Task[Unit] = Task.unit
    override def afterEach(context: AfterEach): Task[Unit]   = Task.unit
    override def afterAll(): Task[Unit]                      = Task.unit
  }
}
