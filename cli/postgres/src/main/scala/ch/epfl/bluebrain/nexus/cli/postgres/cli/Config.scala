package ch.epfl.bluebrain.nexus.cli.postgres.cli

import java.nio.file.Path

import cats.data.EitherT
import cats.effect.{ExitCode, Sync}
import cats.implicits._
import ch.epfl.bluebrain.nexus.cli.Console
import ch.epfl.bluebrain.nexus.cli.error.ConfigError
import ch.epfl.bluebrain.nexus.cli.error.ConfigError.ReadConvertError
import ch.epfl.bluebrain.nexus.cli.postgres.cli.CliOpts._
import ch.epfl.bluebrain.nexus.cli.postgres.config.AppConfig
import ch.epfl.bluebrain.nexus.cli.types.BearerToken
import com.monovore.decline.Opts
import com.typesafe.config.{ConfigRenderOptions, Config => TConfig}
import pureconfig.{ConfigSource, ConfigWriter}

/**
  * CLI configuration specific options.
  */
final class Config[F[_]](console: Console[F])(implicit F: Sync[F]) {

  def subcommand: Opts[F[ExitCode]] =
    Opts.subcommand("config", "Read or write the tool configuration.") {
      show
    }

  def show: Opts[F[ExitCode]] =
    Opts.subcommand("show", "Print the current configuration") {
      (envConfig.orNone, postgresConfig.orNone, token.orNone).mapN {
        case (e, p, t) =>
          loadConfig(e, p, t).flatMap {
            case Right(value) => console.println(renderConfig(value)).as(ExitCode.Success)
            case Left(err)    => F.raiseError(err).as(ExitCode.Error)
          }
      }
    }

  private def renderConfig(cfg: AppConfig): String = {
    val opts = ConfigRenderOptions.concise().setComments(false).setJson(false).setFormatted(true)
    ConfigWriter[AppConfig].to(cfg).render(opts)
  }

  private def loadConfig(
      envFile: Option[Path],
      postgresFile: Option[Path],
      token: Option[Option[BearerToken]]
  ): F[Either[ConfigError, AppConfig]] = {
    val extra = token match {
      case Some(Some(BearerToken(value))) => ConfigSource.string(s"""env.token = "$value"""")
      case _                              => ConfigSource.empty
    }
    val eitherConfig = for {
      source         <- AppConfig.assembleConfig(envFile, postgresFile)
      complete       = extra withFallback source
      cfg            <- EitherT.fromEither[F](complete.load[TConfig].leftMap[ConfigError](ReadConvertError))
      cfgToken       = if (token.contains(None)) cfg.withoutPath("env.token") else cfg
      cfgTokenSource = ConfigSource.fromConfig(cfgToken)
      appConfig      <- EitherT.fromEither[F](cfgTokenSource.load[AppConfig].leftMap[ConfigError](ReadConvertError))
    } yield appConfig
    eitherConfig.value
  }
}

object Config {
  def apply[F[_]: Sync](console: Console[F]): Config[F] = new Config[F](console)
}
