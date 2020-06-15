package ch.epfl.bluebrain.nexus.cli.modules.literature

import cats.Parallel
import cats.effect.{ConcurrentEffect, ContextShift, Timer}
import distage.{ModuleDef, TagK}
import izumi.distage.model.definition.StandardAxis.Repo
import izumi.distage.model.recursive.LocatorRef

final class LiteratureModule[F[_]: Parallel: ContextShift: ConcurrentEffect: Timer: TagK] extends ModuleDef {
  make[Literature[F]].tagged(Repo.Prod).from { locatorRef: LocatorRef => Literature[F](Some(locatorRef)) }

  make[LiteratureProjection[F]].tagged(Repo.Prod)
}

object LiteratureModule {
  final def apply[F[_]: Parallel: ContextShift: ConcurrentEffect: Timer: TagK]: LiteratureModule[F] =
    new LiteratureModule[F]
}
