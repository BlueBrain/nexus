package ch.epfl.bluebrain.nexus.testkit.bio

import cats.effect.Resource
import cats.effect.concurrent.Deferred
import monix.bio.Task
import munit.{FunFixtures, Location, TestOptions}

trait BioFunFixtures extends FunFixtures { self: BioSuite =>

  object ResourceFunFixture {
    def apply[A](resource: Resource[Task, A]): Task[FunFixture[A]] =
      apply(_ => resource)

    def apply[A](resource: TestOptions => Resource[Task, A]): Task[FunFixture[A]] =
      for {
        deferred <- Deferred[Task, Task[Unit]]
        result   <- Task.delay {
                      FunFixture.async[A](
                        setup = { testOptions =>
                          resource(testOptions).allocated.flatMap { case (a, release) =>
                            deferred.complete(release).as(a)
                          }.runToFuture
                        },
                        teardown = { _ => deferred.get.flatten.runToFuture }
                      )
                    }
      } yield result
  }

  implicit class ResourceFixtureOps[A](private val f: Task[FunFixture[A]]) {
    def test(name: String)(body: A => Any)(implicit loc: Location): Unit =
      test(TestOptions(name))(body)

    def test(options: TestOptions)(body: A => Any)(implicit loc: Location): Unit =
      f.runSyncUnsafe().test(options)(body)
  }

}
