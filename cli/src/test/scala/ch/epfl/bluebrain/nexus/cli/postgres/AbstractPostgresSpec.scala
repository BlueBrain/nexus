package ch.epfl.bluebrain.nexus.cli.postgres

import cats.effect.IO
import ch.epfl.bluebrain.nexus.cli.AbstractCliSpec
import ch.epfl.bluebrain.nexus.cli.config.AppConfig
import ch.epfl.bluebrain.nexus.cli.postgres.PostgresDocker.PostgresHostConfig
import doobie.util.transactor.Transactor
import izumi.distage.model.definition.{Module, ModuleDef}

import scala.concurrent.duration._

class AbstractPostgresSpec extends AbstractCliSpec {

  override protected def defaultModules: Module = {
    super.defaultModules ++ new PostgresDocker.Module[IO]
  }

  override def testModule: ModuleDef =
    new ModuleDef {
      make[AppConfig].fromEffect { host: PostgresHostConfig =>
        copyConfigs.flatMap { case (envFile, postgresFile, _) =>
          AppConfig.load[IO](Some(envFile), Some(postgresFile)).flatMap {
            case Left(value)  => IO.raiseError(value)
            case Right(value) =>
              val postgresOffsetFile = postgresFile.getParent.resolve("postgres.offset")
              val cfg                = value.copy(postgres =
                value.postgres.copy(
                  host = host.host,
                  port = host.port,
                  offsetFile = postgresOffsetFile,
                  offsetSaveInterval = 100.milliseconds
                )
              )
              IO.pure(cfg)
          }
        }
      }
      make[Transactor[IO]].fromEffect { (_: PostgresDocker.Container, cfg: AppConfig) =>
        IO {
          Transactor.fromDriverManager[IO](
            "org.postgresql.Driver",
            cfg.postgres.jdbcUrl,
            cfg.postgres.username,
            cfg.postgres.password
          )
        }
      }
    }
}
