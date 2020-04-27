package ch.epfl.bluebrain.nexus.cli

import java.nio.file.{Files, Path}
import java.util.UUID
import java.util.regex.Pattern.quote

import cats.implicits._
import cats.effect.{ContextShift, IO, Timer}
import ch.epfl.bluebrain.nexus.cli.config.{AppConfig, EnvConfig}
import ch.epfl.bluebrain.nexus.cli.dummies.TestCliModule
import ch.epfl.bluebrain.nexus.cli.sse.{OrgLabel, OrgUuid, ProjectLabel, ProjectUuid}
import ch.epfl.bluebrain.nexus.cli.utils.{Randomness, Resources, ShouldMatchers}
import io.circe.Json
import izumi.distage.model.definition.{Module, ModuleDef, StandardAxis}
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

  protected val defaultModules: Module = CliModule[IO] ++ TestCliModule[IO] ++ EffectModule[IO] ++ configModule

  def overrides: ModuleDef = new ModuleDef {
    include(defaultModules)
  }

  override def config: TestConfig = TestConfig(
    pluginConfig = PluginConfig.empty,
    activation = StandardAxis.testDummyActivation,
    moduleOverrides = overrides,
    configBaseName = "cli-test"
  )

  def copyConfigs: IO[Path] = IO {
    val parent  = Files.createTempDirectory(".nexus")
    val envFile = parent.resolve("env.conf")
    Files.copy(getClass.getClassLoader.getResourceAsStream("env.conf"), envFile)
    envFile
  }

  def configModule: ModuleDef = new ModuleDef {
    make[AppConfig].fromEffect {
      copyConfigs.flatMap { envFile =>
        AppConfig.load[IO](Some(envFile)).flatMap {
          case Left(value)  => IO.raiseError(value)
          case Right(value) => IO.pure(value)
        }
      }
    }
    make[EnvConfig].from { cfg: AppConfig => cfg.env }
  }
}
