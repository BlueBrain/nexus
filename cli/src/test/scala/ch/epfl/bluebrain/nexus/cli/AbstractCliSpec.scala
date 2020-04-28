package ch.epfl.bluebrain.nexus.cli

import java.nio.file.{Files, Path}
import java.util.UUID
import java.util.regex.Pattern.quote

import cats.effect.{ContextShift, IO, Timer}
import cats.implicits._
import ch.epfl.bluebrain.nexus.cli.config.{AppConfig, EnvConfig}
import ch.epfl.bluebrain.nexus.cli.dummies.TestCliModule
import ch.epfl.bluebrain.nexus.cli.modules.config.ConfigModule
import ch.epfl.bluebrain.nexus.cli.sse.{OrgLabel, OrgUuid, ProjectLabel, ProjectUuid}
import ch.epfl.bluebrain.nexus.cli.utils.{Randomness, Resources, ShouldMatchers}
import io.circe.Json
import izumi.distage.model.definition.{Module, ModuleDef, StandardAxis}
import izumi.distage.model.reflection.universe.RuntimeDIUniverse.DIKey
import izumi.distage.plugins.PluginConfig
import izumi.distage.testkit.TestConfig
import izumi.distage.testkit.scalatest.DistageSpecScalatest

import scala.concurrent.ExecutionContext

abstract class AbstractCliSpec extends DistageSpecScalatest[IO] with Resources with Randomness with ShouldMatchers {

  implicit protected val cs: ContextShift[IO] = IO.contextShift(ExecutionContext.global)
  implicit protected val tm: Timer[IO]        = IO.timer(ExecutionContext.global)

  protected val orgUuid: OrgUuid           = OrgUuid(UUID.randomUUID())
  protected val projectUuid: ProjectUuid   = ProjectUuid(UUID.randomUUID())
  protected val orgLabel: OrgLabel         = OrgLabel(genString())
  protected val projectLabel: ProjectLabel = ProjectLabel(genString())

  protected val notFoundJson: Json      = jsonContentOf("/templates/not-found.json")
  protected val authFailedJson: Json    = jsonContentOf("/templates/auth-failed.json")
  protected val internalErrorJson: Json = jsonContentOf("/templates/internal-error.json")

  protected val replacements = Map(
    quote("{projectUuid}")  -> projectUuid.show,
    quote("{orgUuid}")      -> orgUuid.show,
    quote("{projectLabel}") -> projectLabel.show,
    quote("{orgLabel}")     -> orgLabel.show
  )

  protected val defaultModules: Module = {
    TestCliModule[IO] ++ EffectModule[IO] ++ ConfigModule[IO] ++ testModule
  }

  def overrides: ModuleDef = new ModuleDef {
    include(defaultModules)
  }

  override def config: TestConfig = TestConfig(
    pluginConfig = PluginConfig.empty,
    activation = StandardAxis.testDummyActivation,
    moduleOverrides = overrides,
    forcedRoots = Set(
      DIKey.get[AppConfig],
      DIKey.get[Console[IO]]
    ),
    configBaseName = "cli-test"
  )

  def copyConfigs: IO[(Path, Path)] = IO {
    val parent       = Files.createTempDirectory(".nexus")
    val envFile      = parent.resolve("env.conf")
    val postgresFile = parent.resolve("postgres.conf")
    Files.copy(getClass.getClassLoader.getResourceAsStream("env.conf"), envFile)
    Files.copy(getClass.getClassLoader.getResourceAsStream("postgres.conf"), postgresFile)
    (envFile, postgresFile)
  }

  def testModule: ModuleDef = new ModuleDef {
    make[AppConfig].fromEffect {
      copyConfigs.flatMap {
        case (envFile, postgresFile) =>
          AppConfig.load[IO](Some(envFile), Some(postgresFile)).flatMap {
            case Left(value)  => IO.raiseError(value)
            case Right(value) => IO.pure(value)
          }
      }
    }
    make[EnvConfig].from { cfg: AppConfig => cfg.env }
  }
}
