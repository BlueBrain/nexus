package ch.epfl.bluebrain.nexus.cli.postgres

import java.nio.file.{Files, Path}

import cats.effect.{ContextShift, IO, Timer}
import ch.epfl.bluebrain.nexus.cli.SharedModule
import ch.epfl.bluebrain.nexus.cli.postgres.PostgresDocker.PostgresHostConfig
import ch.epfl.bluebrain.nexus.cli.postgres.config.AppConfig
import com.github.ghik.silencer.silent
import doobie.util.transactor.Transactor
import izumi.distage.effect.modules.CatsDIEffectModule
import izumi.distage.model.definition.{ModuleDef, StandardAxis}
import izumi.distage.plugins.PluginConfig
import izumi.distage.testkit.TestConfig
import izumi.distage.testkit.scalatest.DistageSpecScalatest

import scala.concurrent.ExecutionContext

class AbstractPostgresSpec extends DistageSpecScalatest[IO] {

  implicit protected val cs: ContextShift[IO] = IO.contextShift(ExecutionContext.global)
  implicit protected val tm: Timer[IO]        = IO.timer(ExecutionContext.global)

  private val copyConfigs: IO[(Path, Path)] = IO {
    val parent       = Files.createTempDirectory(".nexus")
    val envFile      = parent.resolve("env.conf")
    val postgresFile = parent.resolve("postgres.conf")
    Files.copy(getClass.getClassLoader.getResourceAsStream("env.conf"), envFile)
    Files.copy(getClass.getClassLoader.getResourceAsStream("postgres.conf"), postgresFile)
    (envFile, postgresFile)
  }

  @silent
  override def config: TestConfig = TestConfig(
    pluginConfig = PluginConfig.empty,
    activation = StandardAxis.testDummyActivation,
    moduleOverrides = new ModuleDef {
      make[AppConfig]
        .fromEffect { host: PostgresHostConfig =>
          copyConfigs.flatMap {
            case (envFile, postgresFile) =>
              AppConfig.load[IO](Some(envFile), Some(postgresFile)).flatMap {
                case Left(value) => IO.raiseError(value)
                case Right(value) =>
                  IO.pure(value.copy(postgres = value.postgres.copy(host = host.host, port = host.port)))
              }
          }
        }

      make[Transactor[IO]].from { cfg: AppConfig =>
        Transactor.fromDriverManager[IO](
          "org.postgresql.Driver",
          cfg.postgres.jdbcUrl,
          cfg.postgres.username,
          cfg.postgres.password
        )
      }

      include(CatsDIEffectModule)
      include(new PostgresDocker.Module[IO])
      include(SharedModule[IO])
      include(PostgresModule[IO])
    },
    configBaseName = "postgres-test"
  )
}
