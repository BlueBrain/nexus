package ch.epfl.bluebrain.nexus.cli.dummies

import cats.effect.ConcurrentEffect
import ch.epfl.bluebrain.nexus.cli.Console
import distage.{ModuleDef, TagK}
import izumi.distage.model.definition.StandardAxis.Repo

final class TestCliModule[F[_]: ConcurrentEffect: TagK] extends ModuleDef {
  make[TestConsole[F]].tagged(Repo.Dummy).fromEffect(TestConsole[F])
  make[Console[F]].tagged(Repo.Dummy).from { tc: TestConsole[F] => tc }
}

object TestCliModule {

  final def apply[F[_]: ConcurrentEffect: TagK]: TestCliModule[F] =
    new TestCliModule[F]

}
