package ch.epfl.bluebrain.nexus.cli.postgres.cli

import cats.implicits._
import cats.effect.{ExitCode, Sync}
import ch.epfl.bluebrain.nexus.cli.postgres.config.AppConfig
import ch.epfl.bluebrain.nexus.cli.{Console, EventStreamClient, SparqlClient}
import com.github.ghik.silencer.silent
import com.monovore.decline.Opts
import doobie.util.transactor.Transactor

@silent
class PostgresProjection[F[_]](
    eventStream: EventStreamClient[F],
    sparqlClient: SparqlClient[F],
    console: Console[F],
    xa: Transactor[F],
    cfg: AppConfig
)(implicit F: Sync[F]) {

  def subcommand: Opts[F[ExitCode]] =
    Opts.subcommand("projection", "Control the Postgres projection") {
      start
    }

  private def start: Opts[F[ExitCode]] =
    Opts.subcommand("start", "Start the Postgres projection") {
      ???
    }

  private def ddl: F[Unit] = {
    import doobie._
    import doobie.implicits._
    val ddls = for {
      projectConfig <- cfg.postgres.projects.values.toList
      typeConfig    <- projectConfig.types
      queryConfig   <- typeConfig.queries
    } yield Fragment.const(queryConfig.ddl).update.run
    val conn = ddls.sequence
    conn.transact(xa) >> F.unit
  }

}
