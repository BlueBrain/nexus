package ch.epfl.bluebrain.nexus.cli

import cats.effect.ConcurrentEffect
import distage.{ModuleDef, TagK}
import izumi.distage.model.definition.StandardAxis.Repo

final class CliModule[F[_]: ConcurrentEffect: TagK] extends ModuleDef {
  make[Console[F]].tagged(Repo.Prod).from(Console[F])
}

object CliModule {

  final def apply[F[_]: ConcurrentEffect: TagK]: CliModule[F] =
    new CliModule[F]

}
