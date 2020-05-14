package ch.epfl.bluebrain.nexus.cli.modules.config

import cats.Parallel
import cats.effect.{ConcurrentEffect, ContextShift, Timer}
import distage.{ModuleDef, TagK}

final class ConfigModule[F[_]: Parallel: ContextShift: ConcurrentEffect: Timer: TagK] extends ModuleDef {
  make[Config[F]]
  make[ConfigShow[F]]
}

object ConfigModule {
  final def apply[F[_]: Parallel: ContextShift: ConcurrentEffect: Timer: TagK]: ConfigModule[F] = new ConfigModule[F]
}
