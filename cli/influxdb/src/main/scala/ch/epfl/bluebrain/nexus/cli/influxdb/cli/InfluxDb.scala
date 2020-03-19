package ch.epfl.bluebrain.nexus.cli.influxdb.cli

import java.nio.file.Path

import cats.data.ValidatedNel
import cats.effect.{Concurrent, ConcurrentEffect, ContextShift, ExitCode, Timer}
import cats.implicits._
import ch.epfl.bluebrain.nexus.cli.config.NexusConfig
import ch.epfl.bluebrain.nexus.cli.influxdb.InfluxDbIndexer
import ch.epfl.bluebrain.nexus.cli.influxdb.cli.InfluxDb._
import ch.epfl.bluebrain.nexus.cli.influxdb.config.InfluxDbConfig
import ch.epfl.bluebrain.nexus.cli.influxdb.modules.{ConfigModule, InfluxDbModule}
import ch.epfl.bluebrain.nexus.cli.types.BearerToken
import com.monovore.decline.Opts
import distage.Injector
import izumi.distage.model.definition.Activation
import izumi.distage.model.definition.StandardAxis.Repo
import izumi.distage.model.plan.GCMode
import izumi.fundamentals.reflection.Tags.TagK
import org.http4s.Uri
import pureconfig.generic.auto._

import scala.util.Try

class InfluxDb[F[_]: ConcurrentEffect: Concurrent: ContextShift: Timer: TagK] {
  def subcommand: Opts[F[ExitCode]] = {

    Opts.subcommand("index", "Index data into the target system") {
      (influxDbEndpoint, nexusEndpoint, nexusToken, configFile, restartFlag).mapN {
        (influxEndpointOpt, nexusEndpointOpt, nexusTokenOpt, configFileOpt, restart) =>
          for {
            influxDbConfig <- InfluxDbConfig.withDefaults(configFileOpt, influxEndpointOpt).liftTo[F]
            nexusConfig    <- NexusConfig.withDefaults(configFileOpt, nexusEndpointOpt, nexusTokenOpt).liftTo[F]
            configModule = ConfigModule(nexusConfig, influxDbConfig)
            influxModule = InfluxDbModule[F]
            modules      = influxModule ++ configModule
            result <- Injector(Activation(Repo -> Repo.Prod))
              .produceF[F](modules, GCMode.NoGC)
              .use { locator =>
                locator.get[InfluxDbIndexer[F]].index(restart).as(ExitCode.Success)
              }
          } yield result
      }
    }
  }
}

object InfluxDb {

  val influxDbEndpoint = Opts
    .option[String](
      long = "influxdb-endpoint",
      help = "The base address of InfluxDb"
    )
    .mapValidated(validateUri)
    .orNone

  val nexusEndpoint = Opts
    .option[String](
      long = "nexus-endpoint",
      help = "The base address of Nexus deployment"
    )
    .mapValidated(validateUri)
    .orNone

  val nexusToken: Opts[Option[BearerToken]] = Opts
    .option[String](
      long = "nexus-token",
      help = "The token used to communicate with Nexus"
    )
    .map(BearerToken)
    .orNone

  val configFile: Opts[Option[Path]] = Opts
    .option[String](
      long = "config-file",
      help = "The path to the config file."
    )
    .mapValidated(validatePath)
    .orNone

  val restartFlag = Opts.flag("restart", help = "Whether to restart the indexing from the beginning.").orFalse

  private def validateUri(str: String): ValidatedNel[String, Uri] =
    Uri
      .fromString(str)
      .leftMap(_ => s"Invalid Uri: '$str'")
      .ensure(s"Invalid Uri: '$str'")(uri => uri.scheme.isDefined)
      .toValidatedNel

  private def validatePath(str: String): ValidatedNel[String, Path] =
    Try(Path.of(str)).toEither.leftMap(_ => s"Invalid Path: '$str'").toValidatedNel

  val restart: Opts[Unit] = Opts
    .flag(long = "restart", help = "Restart the index process from the beginning")

  def apply[F[_]: ConcurrentEffect: Concurrent: ContextShift: Timer: TagK]: InfluxDb[F] = new InfluxDb[F]
}
