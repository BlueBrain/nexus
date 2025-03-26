package ch.epfl.bluebrain.nexus.testkit.actor

import akka.actor.ActorSystem
import cats.effect.{IO, Resource}
import munit.CatsEffectSuite
import munit.catseffect.IOFixture

object ActorSystemSetup {

  def resource(): Resource[IO, ActorSystem] =
    Resource.make(IO.delay(ActorSystem()))(as => IO.fromFuture(IO(as.terminate())).void)

  trait Fixture { self: CatsEffectSuite =>
    val actorSystem: IOFixture[ActorSystem] = ResourceSuiteLocalFixture("actorsystem", resource())
  }
}
