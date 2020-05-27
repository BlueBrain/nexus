package ch.epfl.bluebrain.nexus.cli.modules.config

import distage.{ModuleDef, TagK}

final class ConfigModule[F[_]: TagK] extends ModuleDef {
  make[Config[F]]
  make[ConfigShow[F]]
}

object ConfigModule {
  final def apply[F[_]: TagK]: ConfigModule[F] = new ConfigModule[F]
}
