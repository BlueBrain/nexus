package ch.epfl.bluebrain.nexus.cli.postgres

import cats.effect.ConcurrentEffect
import ch.epfl.bluebrain.nexus.cli.Console
import ch.epfl.bluebrain.nexus.cli.postgres.cli.Cli
import distage.TagK
import izumi.distage.model.definition.ModuleDef
import izumi.distage.model.definition.StandardAxis.Repo

class PostgresModule[F[_]: ConcurrentEffect: TagK] extends ModuleDef {
  make[Cli[F]].tagged(Repo.Dummy).from { console: Console[F] => new Cli[F](console) }
  make[Cli[F]].tagged(Repo.Prod).from { console: Console[F] => new Cli[F](console) }
}

object PostgresModule {
  def apply[F[_]: ConcurrentEffect: TagK]: PostgresModule[F] = new PostgresModule[F]
}
