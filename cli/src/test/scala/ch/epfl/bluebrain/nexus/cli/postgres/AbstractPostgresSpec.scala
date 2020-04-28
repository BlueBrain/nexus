package ch.epfl.bluebrain.nexus.cli.postgres

import cats.effect.IO
import ch.epfl.bluebrain.nexus.cli.AbstractCliSpec
import ch.epfl.bluebrain.nexus.cli.config.AppConfig
import ch.epfl.bluebrain.nexus.cli.postgres.PostgresDocker.PostgresHostConfig
import izumi.distage.model.definition.{Module, ModuleDef}
import izumi.distage.model.reflection.universe.RuntimeDIUniverse.DIKey
import izumi.distage.testkit.TestConfig

class AbstractPostgresSpec extends AbstractCliSpec {

  override protected def defaultModules: Module = {
    super.defaultModules ++ new PostgresDocker.Module[IO]
  }

  override def config: TestConfig = {
    super.config.copy(
      memoizationRoots = Set(DIKey.get[PostgresDocker.Container])
    )
  }

  override def testModule: ModuleDef = new ModuleDef {
    make[AppConfig].fromEffect { host: PostgresHostConfig =>
      copyConfigs.flatMap {
        case (envFile, postgresFile) =>
          AppConfig.load[IO](Some(envFile), Some(postgresFile)).flatMap {
            case Left(value)  => IO.raiseError(value)
            case Right(value) => IO.pure(value.copy(postgres = value.postgres.copy(host = host.host, port = host.port)))
          }
      }
    }
  }
}
