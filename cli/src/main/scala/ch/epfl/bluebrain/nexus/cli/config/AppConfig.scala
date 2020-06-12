package ch.epfl.bluebrain.nexus.cli.config

import java.nio.file.{Files, Path, Paths}

import cats.data.EitherT
import cats.effect.Sync
import cats.implicits._
import ch.epfl.bluebrain.nexus.cli.CliError.ConfigError
import ch.epfl.bluebrain.nexus.cli.CliError.ConfigError.{ReadConvertError, UserHomeNotDefined}
import ch.epfl.bluebrain.nexus.cli.config.influx.InfluxConfig
import ch.epfl.bluebrain.nexus.cli.config.postgres.PostgresConfig
import ch.epfl.bluebrain.nexus.cli.sse.BearerToken
import com.typesafe.config.{Config => TConfig}
import pureconfig.generic.semiauto.deriveConvert
import pureconfig.{ConfigConvert, ConfigObjectSource, ConfigSource}

/**
  * Complete application configuration.
  *
  * @param env      the environment configuration
  * @param postgres the postgres configuration
  * @param influx   the influxDB configuration
  */
final case class AppConfig(
    env: EnvConfig,
    postgres: PostgresConfig,
    influx: InfluxConfig
)

object AppConfig {

  /**
    * Loads the application configuration using possible file config location overrides.
    *
    * @param envConfigFile      an optional env config file location
    * @param postgresConfigFile an optional postgres config file location
    * @param influxConfigFile   an optional influx config file location
    * @param token              an optional token configuration None - unset, Some(None) - wipe token, Some(Some) -
    *                           overridden token
    */
  def load[F[_]](
      envConfigFile: Option[Path] = None,
      postgresConfigFile: Option[Path] = None,
      influxConfigFile: Option[Path] = None,
      token: Option[Option[BearerToken]] = None
  )(implicit F: Sync[F]): F[Either[ConfigError, AppConfig]] =
    (for {
      cfg    <- assembleConfig(envConfigFile, postgresConfigFile, influxConfigFile, token)
      appCfg <- EitherT.fromEither[F](cfg.load[AppConfig].leftMap[ConfigError](ReadConvertError))
    } yield appCfg).value

  /**
    * Assembles the configuration using possible file config location overrides.
    *
    * @param envConfigFile      an optional env config file location
    * @param postgresConfigFile an optional postgres config file location
    * @param influxConfigFile   an optional influx config file location
    * @param token              an optional token configuration None - unset, Some(None) - wipe token, Some(Some) -
    *                           overridden token
    */
  def assembleConfig[F[_]](
      envConfigFile: Option[Path] = None,
      postgresConfigFile: Option[Path] = None,
      influxConfigFile: Option[Path] = None,
      token: Option[Option[BearerToken]] = None
  )(implicit F: Sync[F]): EitherT[F, ConfigError, ConfigObjectSource] = {

    val nexusHome: Either[ConfigError, Path] =
      sys.props.get("user.home").toRight(UserHomeNotDefined).map(home => Paths.get(home, ".nexus"))
    val envFile                              = nexusHome.map { value => envConfigFile.getOrElse(value.resolve("env.conf")) }
    val postgresFile                         = nexusHome.map { value => postgresConfigFile.getOrElse(value.resolve("postgres.conf")) }
    val influxFile                           = nexusHome.map { value => influxConfigFile.getOrElse(value.resolve("influx.conf")) }

    def loadFileIfExists(file: Path): EitherT[F, ConfigError, ConfigObjectSource] = {
      EitherT(F.delay {
        if (Files.exists(file) && Files.isReadable(file) && Files.isRegularFile(file)) Right(ConfigSource.file(file))
        else Right(ConfigSource.empty)
      })
    }

    val extra = token match {
      case Some(Some(BearerToken(value))) => ConfigSource.string(s"""env.token = "$value"""")
      case _                              => ConfigSource.empty
    }

    for {
      envFile      <- EitherT.fromEither[F](envFile)
      postgresFile <- EitherT.fromEither[F](postgresFile)
      influxFile   <- EitherT.fromEither[F](influxFile)
      env          <- loadFileIfExists(envFile)
      postgres     <- loadFileIfExists(postgresFile)
      influx       <- loadFileIfExists(influxFile)
      overrides     = ConfigSource.defaultOverrides
      reference     = ConfigSource.defaultReference
      stack         =
        extra withFallback overrides withFallback postgres withFallback influx withFallback env withFallback reference
      cfg          <- EitherT(F.delay(stack.load[TConfig].leftMap[ConfigError](ReadConvertError)))
      cfgToken      = if (token.contains(None)) cfg.withoutPath("env.token") else cfg
      source        = ConfigSource.fromConfig(cfgToken)
    } yield source
  }

  implicit final val appConfigConvert: ConfigConvert[AppConfig] =
    deriveConvert[AppConfig]

}
