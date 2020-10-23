package ch.epfl.bluebrain.nexus.cli

import java.nio.file.{Files, Path}
import java.util.UUID
import java.util.regex.Pattern.quote

import cats.effect.{ContextShift, IO, Timer}
import cats.implicits._
import ch.epfl.bluebrain.nexus.cli.clients.InfluxClient
import ch.epfl.bluebrain.nexus.cli.config.{AppConfig, EnvConfig}
import ch.epfl.bluebrain.nexus.cli.dummies.TestCliModule
import ch.epfl.bluebrain.nexus.cli.modules.config.ConfigModule
import ch.epfl.bluebrain.nexus.cli.modules.influx.InfluxModule
import ch.epfl.bluebrain.nexus.cli.modules.postgres.PostgresModule
import ch.epfl.bluebrain.nexus.cli.sse.{Event, OrgLabel, OrgUuid, ProjectLabel, ProjectUuid}
import ch.epfl.bluebrain.nexus.cli.utils.{Randomness, Resources, ShouldMatchers}
import doobie.util.transactor.Transactor
import io.circe.Json
import izumi.distage.model.definition.{Module, ModuleDef, StandardAxis}
import izumi.distage.model.reflection.DIKey
import izumi.distage.plugins.PluginConfig
import izumi.distage.testkit.TestConfig
import izumi.distage.testkit.scalatest.DistageSpecScalatest
import org.scalatest.OptionValues

import scala.concurrent.ExecutionContext

abstract class AbstractCliSpec
    extends DistageSpecScalatest[IO]
    with Resources
    with Randomness
    with ShouldMatchers
    with OptionValues {

  implicit protected val cs: ContextShift[IO] = IO.contextShift(ExecutionContext.global)
  implicit protected val tm: Timer[IO]        = IO.timer(ExecutionContext.global)

  protected val orgUuid: OrgUuid           = OrgUuid(UUID.randomUUID())
  protected val projectUuid: ProjectUuid   = ProjectUuid(UUID.randomUUID())
  protected val orgLabel: OrgLabel         = OrgLabel(genString())
  protected val projectLabel: ProjectLabel = ProjectLabel(genString())

  protected val eventsJson: Json    = jsonContentOf("/events.json")
  protected val events: List[Event] = eventsJson.as[List[Event]].toOption.value

  protected val notFoundJson: Json      = jsonContentOf("/templates/not-found.json")
  protected val authFailedJson: Json    = jsonContentOf("/templates/auth-failed.json")
  protected val internalErrorJson: Json = jsonContentOf("/templates/internal-error.json")

  protected val replacements = Map(
    quote("{projectUuid}")  -> projectUuid.show,
    quote("{orgUuid}")      -> orgUuid.show,
    quote("{projectLabel}") -> projectLabel.show,
    quote("{orgLabel}")     -> orgLabel.show
  )

  protected def defaultModules: Module = {
    TestCliModule[IO](events) ++
      EffectModule[IO] ++
      ConfigModule[IO] ++
      PostgresModule[IO] ++
      InfluxModule[IO] ++
      testModule
  }

  def overrides: ModuleDef =
    new ModuleDef {
      include(defaultModules)
    }

  override def config: TestConfig =
    TestConfig(
      pluginConfig = PluginConfig.empty,
      activation = StandardAxis.testDummyActivation,
      moduleOverrides = overrides,
      memoizationRoots = super.config.memoizationRoots ++ Set(
        DIKey.get[Transactor[IO]],
        DIKey.get[InfluxClient[IO]]
      ),
      forcedRoots = super.config.forcedRoots ++ Set(
        DIKey.get[AppConfig],
        DIKey.get[Console[IO]]
      ),
      configBaseName = s"${getClass.getSimpleName}-cli-test"
    )

  def copyConfigs: IO[(Path, Path, Path)] =
    IO {
      val parent       = Files.createTempDirectory(".nexus")
      val envFile      = parent.resolve("env.conf")
      val postgresFile = parent.resolve("postgres.conf")
      val influxFile   = parent.resolve("influx.conf")
      Files.copy(getClass.getClassLoader.getResourceAsStream("env.conf"), envFile)
      Files.copy(getClass.getClassLoader.getResourceAsStream("postgres.conf"), postgresFile)
      Files.copy(getClass.getClassLoader.getResourceAsStream("influx.conf"), influxFile)
      (envFile, postgresFile, influxFile)
    }

  def testModule: ModuleDef =
    new ModuleDef {
      make[AppConfig].fromEffect {
        copyConfigs.flatMap { case (envFile, postgresFile, influxFile) =>
          val postgresOffsetFile = postgresFile.getParent.resolve("postgres.offset")
          val influxOffsetFile   = influxFile.getParent.resolve("influx.offset")
          AppConfig.load[IO](Some(envFile), Some(postgresFile), Some(influxFile)).flatMap {
            case Left(value)  => IO.raiseError(value)
            case Right(value) =>
              IO.pure(
                value.copy(
                  postgres = value.postgres.copy(offsetFile = postgresOffsetFile),
                  influx = value.influx.copy(offsetFile = influxOffsetFile)
                )
              )
          }
        }
      }
      make[EnvConfig].from { cfg: AppConfig => cfg.env }
    }
}
