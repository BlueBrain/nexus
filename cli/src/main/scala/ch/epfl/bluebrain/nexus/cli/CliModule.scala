package ch.epfl.bluebrain.nexus.cli

import cats.effect.concurrent.Ref
import cats.effect.{ConcurrentEffect, Timer}
import cats.implicits._
import ch.epfl.bluebrain.nexus.cli.clients._
import ch.epfl.bluebrain.nexus.cli.config.AppConfig
import ch.epfl.bluebrain.nexus.cli.sse.{OrgLabel, OrgUuid, ProjectLabel, ProjectUuid}
import distage.{ModuleDef, TagK}
import izumi.distage.model.definition.StandardAxis.Repo
import org.http4s.client.Client
import org.http4s.client.blaze.BlazeClientBuilder

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.Duration

final class CliModule[F[_]: ConcurrentEffect: Timer: TagK] extends ModuleDef {

  make[Console[F]].tagged(Repo.Prod).from(Console[F])

  make[Client[F]].tagged(Repo.Prod).fromResource {
    BlazeClientBuilder[F](ExecutionContext.global).withIdleTimeout(Duration.Inf).resource
  }

  make[ProjectClient[F]].tagged(Repo.Prod).fromEffect { (cfg: AppConfig, client: Client[F], console: Console[F]) =>
    Ref.of[F, Map[(OrgUuid, ProjectUuid), (OrgLabel, ProjectLabel)]](Map.empty).map { cache =>
      ProjectClient(client, cfg.env, cache, console)
    }
  }

  make[SparqlClient[F]].tagged(Repo.Prod).from { (cfg: AppConfig, client: Client[F], console: Console[F]) =>
    SparqlClient(client, cfg.env, console)
  }

  make[EventStreamClient[F]].tagged(Repo.Prod).from { (cfg: AppConfig, client: Client[F], pc: ProjectClient[F]) =>
    EventStreamClient(client, pc, cfg.env)
  }

  make[InfluxClient[F]].tagged(Repo.Prod).from { (cfg: AppConfig, client: Client[F], console: Console[F]) =>
    InfluxClient(client, cfg, console)
  }
}

object CliModule {

  final def apply[F[_]: ConcurrentEffect: Timer: TagK]: CliModule[F] =
    new CliModule[F]

}
