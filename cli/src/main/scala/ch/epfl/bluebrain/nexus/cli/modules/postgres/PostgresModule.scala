package ch.epfl.bluebrain.nexus.cli.modules.postgres

import cats.Parallel
import cats.effect.{ConcurrentEffect, ContextShift, Timer}
import ch.epfl.bluebrain.nexus.cli.config.AppConfig
import distage.{ModuleDef, TagK}
import doobie.util.transactor.Transactor
import izumi.distage.model.definition.StandardAxis.Repo

final class PostgresModule[F[_]: Parallel: ContextShift: ConcurrentEffect: Timer: TagK] extends ModuleDef {
  make[Postgres[F]]
  make[PostgresProjection[F]]
  make[Transactor[F]].tagged(Repo.Prod).from { (cfg: AppConfig) =>
    Transactor.fromDriverManager[F](
      "org.postgresql.Driver",
      cfg.postgres.jdbcUrl,
      cfg.postgres.username,
      cfg.postgres.password
    )
  }
}

object PostgresModule {
  final def apply[F[_]: Parallel: ContextShift: ConcurrentEffect: Timer: TagK]: PostgresModule[F] =
    new PostgresModule[F]
}
