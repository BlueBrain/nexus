package ch.epfl.bluebrain.nexus.cli.config

import java.nio.file.{Files, Path, Paths}

import cats.data.EitherT
import cats.effect.Sync
import cats.implicits._
import ch.epfl.bluebrain.nexus.cli.CliError.ConfigError
import ch.epfl.bluebrain.nexus.cli.CliError.ConfigError.{ReadConvertError, UserHomeNotDefined}
import pureconfig.generic.semiauto.deriveConvert
import pureconfig.{ConfigConvert, ConfigObjectSource, ConfigSource}

/**
  * Complete application configuration.
  *
  * @param env the environment configuration
  */
final case class AppConfig(
    env: EnvConfig
)

object AppConfig {

  /**
    * Loads the application configuration using possible file config location overrides.
    *
    * @param envConfigFile an optional env config file location
    */
  def load[F[_]](
      envConfigFile: Option[Path] = None
  )(implicit F: Sync[F]): F[Either[ConfigError, AppConfig]] =
    loadT[F](envConfigFile).value

  /**
    * Loads the application configuration using possible overrides.
    *
    * @param envConfigFile an optional env config file location
    */
  def loadT[F[_]](
      envConfigFile: Option[Path] = None
  )(implicit F: Sync[F]): EitherT[F, ConfigError, AppConfig] =
    for {
      cfg    <- assembleConfig(envConfigFile)
      appCfg <- EitherT.fromEither[F](cfg.load[AppConfig].leftMap[ConfigError](ReadConvertError))
    } yield appCfg

  /**
    * Assembles the configuration using possible file config location overrides.
    *
    * @param envConfigFile an optional env config file location
    */
  def assembleConfig[F[_]](
      envConfigFile: Option[Path] = None
  )(implicit F: Sync[F]): EitherT[F, ConfigError, ConfigObjectSource] = {
    val nexusHome: Either[ConfigError, Path] =
      sys.props.get("user.home").toRight(UserHomeNotDefined).map(home => Paths.get(home, ".nexus"))
    val envFile = nexusHome.map { value => envConfigFile.getOrElse(value.resolve("env.conf")) }

    def loadFileIfExists(file: Path): EitherT[F, ConfigError, ConfigObjectSource] = {
      EitherT(F.delay {
        if (Files.exists(file) && Files.isReadable(file) && Files.isRegularFile(file)) Right(ConfigSource.file(file))
        else Right(ConfigSource.empty)
      })
    }

    for {
      envFile   <- EitherT.fromEither[F](envFile)
      env       <- loadFileIfExists(envFile)
      overrides = ConfigSource.defaultOverrides
      reference = ConfigSource.defaultReference
      stack     = overrides withFallback env withFallback reference
    } yield stack
  }

  implicit final val appConfigConvert: ConfigConvert[AppConfig] =
    deriveConvert[AppConfig]

}
