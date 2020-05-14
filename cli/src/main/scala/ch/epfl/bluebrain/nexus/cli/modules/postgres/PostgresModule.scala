package ch.epfl.bluebrain.nexus.cli.modules.postgres

import cats.effect.{Async, ContextShift}
import ch.epfl.bluebrain.nexus.cli.config.AppConfig
import distage.{ModuleDef, TagK}
import doobie.util.transactor.Transactor
import izumi.distage.model.definition.StandardAxis.Repo

final class PostgresModule[F[_]: Async: ContextShift: TagK] extends ModuleDef {
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
  final def apply[F[_]: Async: ContextShift: TagK]: PostgresModule[F] =
    new PostgresModule[F]
}
