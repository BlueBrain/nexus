package ch.epfl.bluebrain.nexus.cli.modules.influx

import distage.{ModuleDef, TagK}

final class InfluxModule[F[_]: TagK] extends ModuleDef {
  make[Influx[F]]
  make[InfluxProjection[F]]
}

object InfluxModule {
  final def apply[F[_]: TagK]: InfluxModule[F] =
    new InfluxModule[F]
}
