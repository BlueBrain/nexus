package ch.epfl.bluebrain.nexus.cli.influxdb.cli

import cats.data.ValidatedNel
import cats.effect.{Concurrent, ConcurrentEffect, ContextShift, ExitCode, Timer}
import cats.implicits._
import ch.epfl.bluebrain.nexus.cli.config.NexusConfig
import ch.epfl.bluebrain.nexus.cli.influxdb.InfluxDbIndexer
import ch.epfl.bluebrain.nexus.cli.influxdb.cli.InfluxDb._
import ch.epfl.bluebrain.nexus.cli.influxdb.config.InfluxDbConfig
import ch.epfl.bluebrain.nexus.cli.influxdb.config.InfluxDbConfig._
import ch.epfl.bluebrain.nexus.cli.influxdb.modules.{ConfigModule, InfluxDbModule}
import com.monovore.decline.Opts
import com.typesafe.config.ConfigFactory
import distage.Injector
import izumi.distage.model.definition.Activation
import izumi.distage.model.definition.StandardAxis.Repo
import izumi.distage.model.plan.GCMode
import izumi.fundamentals.reflection.Tags.TagK
import org.http4s.Uri
import pureconfig.ConfigSource
import pureconfig.generic.auto._

class InfluxDb[F[_]: ConcurrentEffect: Concurrent: ContextShift: Timer: TagK] {
  def subcommand: Opts[F[ExitCode]] = {

    Opts.subcommand("index", "Index data into the target system") {

      influxDbEndpoint.orNone.map { endpointOpt =>
        val rawInfluxDbConfig = ConfigSource
          .fromConfig(ConfigFactory.parseResources("influxdb.conf"))
          .at("influxdb")
          .loadOrThrow[InfluxDbConfig]

        val influxDbConfig = endpointOpt match {
          case Some(endpoint) => rawInfluxDbConfig.copy(client = rawInfluxDbConfig.client.copy(endpoint = endpoint))
          case None           => rawInfluxDbConfig
        }

        val nexusConfig  = NexusConfig().getOrElse(throw new IllegalArgumentException)
        val configModule = ConfigModule(nexusConfig, influxDbConfig)
        val influxModule = InfluxDbModule[F]
        val modules      = influxModule ++ configModule

        Injector(Activation(Repo -> Repo.Prod))
          .produceF[F](modules, GCMode.NoGC)
          .use { locator =>
            locator.get[InfluxDbIndexer[F]].index().as(ExitCode.Success)
          }
      }
    }
  }
}

object InfluxDb {

  val influxDbEndpoint: Opts[Uri] = Opts
    .option[String](
      long = "influxd-endpoint",
      help = "The base address of Elasticsearch"
    )
    .mapValidated(validateUri)

  private def validateUri(str: String): ValidatedNel[String, Uri] =
    Uri
      .fromString(str)
      .leftMap(_ => s"Invalid Uri: '$str'")
      .ensure(s"Invalid Uri: '$str'")(uri => uri.scheme.isDefined)
      .toValidatedNel
  val restart: Opts[Unit] = Opts
    .flag(long = "restart", help = "Restart the index process from the beginning")

  def apply[F[_]: ConcurrentEffect: Concurrent: ContextShift: Timer: TagK]: InfluxDb[F] = new InfluxDb[F]
}
