package ch.epfl.bluebrain.nexus.cli.config

import java.nio.file.{Files, Path, Paths}

import cats.data.EitherT
import cats.effect.Sync
import cats.implicits._
import ch.epfl.bluebrain.nexus.cli.CliError.ConfigError
import ch.epfl.bluebrain.nexus.cli.CliError.ConfigError.{ReadConvertError, UserHomeNotDefined}
import ch.epfl.bluebrain.nexus.cli.config.postgres.PostgresConfig
import pureconfig.generic.semiauto.deriveConvert
import pureconfig.{ConfigConvert, ConfigObjectSource, ConfigSource}

/**
  * Complete application configuration.
  *
  * @param env      the environment configuration
  * @param postgres the postgres configuration
  */
final case class AppConfig(
    env: EnvConfig,
    postgres: PostgresConfig
)

object AppConfig {

  /**
    * Loads the application configuration using possible file config location overrides.
    *
    * @param envConfigFile      an optional env config file location
    * @param postgresConfigFile an optional postgres config file location
    */
  def load[F[_]](
      envConfigFile: Option[Path] = None,
      postgresConfigFile: Option[Path] = None
  )(implicit F: Sync[F]): F[Either[ConfigError, AppConfig]] =
    loadT[F](envConfigFile, postgresConfigFile).value

  /**
    * Loads the application configuration using possible overrides.
    *
    * @param envConfigFile      an optional env config file location
    * @param postgresConfigFile an optional postgres config file location
    */
  def loadT[F[_]](
      envConfigFile: Option[Path] = None,
      postgresConfigFile: Option[Path] = None
  )(implicit F: Sync[F]): EitherT[F, ConfigError, AppConfig] =
    for {
      cfg    <- assembleConfig(envConfigFile, postgresConfigFile)
      appCfg <- EitherT.fromEither[F](cfg.load[AppConfig].leftMap[ConfigError](ReadConvertError))
    } yield appCfg

  /**
    * Assembles the configuration using possible file config location overrides.
    *
    * @param envConfigFile      an optional env config file location
    * @param postgresConfigFile an optional postgres config file location
    */
  def assembleConfig[F[_]](
      envConfigFile: Option[Path] = None,
      postgresConfigFile: Option[Path] = None
  )(implicit F: Sync[F]): EitherT[F, ConfigError, ConfigObjectSource] = {
    val nexusHome: Either[ConfigError, Path] =
      sys.props.get("user.home").toRight(UserHomeNotDefined).map(home => Paths.get(home, ".nexus"))
    val envFile      = nexusHome.map { value => envConfigFile.getOrElse(value.resolve("env.conf")) }
    val postgresFile = nexusHome.map { value => postgresConfigFile.getOrElse(value.resolve("postgres.conf")) }

    def loadFileIfExists(file: Path): EitherT[F, ConfigError, ConfigObjectSource] = {
      EitherT(F.delay {
        if (Files.exists(file) && Files.isReadable(file) && Files.isRegularFile(file)) Right(ConfigSource.file(file))
        else Right(ConfigSource.empty)
      })
    }

    for {
      envFile      <- EitherT.fromEither[F](envFile)
      postgresFile <- EitherT.fromEither[F](postgresFile)
      env          <- loadFileIfExists(envFile)
      postgres     <- loadFileIfExists(postgresFile)
      overrides    = ConfigSource.defaultOverrides
      reference    = ConfigSource.defaultReference
      stack        = overrides withFallback postgres withFallback env withFallback reference
    } yield stack
  }

  implicit final val appConfigConvert: ConfigConvert[AppConfig] =
    deriveConvert[AppConfig]

}
