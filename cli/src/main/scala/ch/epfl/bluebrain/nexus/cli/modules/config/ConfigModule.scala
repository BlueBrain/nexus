package ch.epfl.bluebrain.nexus.cli.modules.config

import cats.Parallel
import cats.effect.{ConcurrentEffect, ContextShift, Timer}
import distage.{ModuleDef, TagK}
import izumi.distage.model.definition.StandardAxis.Repo
import izumi.distage.model.recursive.LocatorRef

final class ConfigModule[F[_]: Parallel: ContextShift: ConcurrentEffect: Timer: TagK] extends ModuleDef {
  make[Config[F]].tagged(Repo.Prod).from { locatorRef: LocatorRef => Config[F](Some(locatorRef)) }
}

object ConfigModule {
  final def apply[F[_]: Parallel: ContextShift: ConcurrentEffect: Timer: TagK]: ConfigModule[F] = new ConfigModule[F]
}
