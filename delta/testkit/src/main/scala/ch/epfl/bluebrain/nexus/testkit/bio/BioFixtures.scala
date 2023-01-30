package ch.epfl.bluebrain.nexus.testkit.bio

import cats.effect.Resource
import ResourceFixture.TaskFixture
import monix.bio.Task

// adapted from https://github.com/typelevel/munit-cats-effect/blob/main/core/src/main/scala/munit/CatsEffectFixtures.scala
trait BioFixtures {

  object ResourceTestLocalFixture {
    def apply[A](name: String, resource: Resource[Task, A]): TaskFixture[A] =
      ResourceFixture.testLocal(name, resource)
  }

  object ResourceSuiteLocalFixture {
    def apply[A](name: String, resource: Resource[Task, A]): TaskFixture[A] =
      ResourceFixture.suiteLocal(name, resource)
  }
}
