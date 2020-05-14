package ch.epfl.bluebrain.nexus.cli.modules.influx

import cats.Parallel
import cats.effect.{ConcurrentEffect, ContextShift, Timer}
import distage.{ModuleDef, TagK}
import izumi.distage.model.definition.StandardAxis.Repo

final class InfluxModule[F[_]: Parallel: ContextShift: ConcurrentEffect: Timer: TagK] extends ModuleDef {
  make[Influx[F]]
  make[InfluxProjection[F]]
}

object InfluxModule {
  final def apply[F[_]: Parallel: ContextShift: ConcurrentEffect: Timer: TagK]: InfluxModule[F] =
    new InfluxModule[F]
}
