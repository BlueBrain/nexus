package ch.epfl.bluebrain.nexus.cli.postgres.config

import java.nio.file.{Files, Path, Paths}

import cats.data.EitherT
import cats.effect.Sync
import cats.implicits._
import ch.epfl.bluebrain.nexus.cli.config.NexusConfig
import ch.epfl.bluebrain.nexus.cli.error.ConfigError
import ch.epfl.bluebrain.nexus.cli.error.ConfigError.{ReadConvertError, UserHomeNotDefined}
import com.typesafe.config.{Config, ConfigFactory}
import pureconfig.backend.ConfigFactoryWrapper
import pureconfig.generic.semiauto.deriveConvert
import pureconfig.{ConfigConvert, ConfigReader, ConfigSource}

/**
  * Complete application configuration.
  */
final case class AppConfig(
    env: NexusConfig,
    postgres: PostgresConfig
)

object AppConfig {

  def load[F[_]: Sync](
      envConfigFile: Option[Path] = None,
      postgresConfigFile: Option[Path] = None
  ): F[Either[ConfigError, AppConfig]] =
    loadT[F](envConfigFile, postgresConfigFile).value

  def loadT[F[_]](
      envConfigFile: Option[Path] = None,
      postgresConfigFile: Option[Path] = None
  )(implicit F: Sync[F]): EitherT[F, ConfigError, AppConfig] = {

    val nexusHome: Either[ConfigError, Path] =
      sys.props.get("user.home").toRight(UserHomeNotDefined).map(home => Paths.get(home, ".nexus"))
    val envFile      = nexusHome.map { value => envConfigFile.getOrElse(value.resolve("env.conf")) }
    val postgresFile = nexusHome.map { value => postgresConfigFile.getOrElse(value.resolve("postgres.conf")) }

    def liftT(value: => ConfigReader.Result[Config]): EitherT[F, ConfigError, Config] =
      EitherT(F.delay(value.leftMap(ReadConvertError)))

    def loadFileIfExists(file: Path): EitherT[F, ConfigError, Config] = {
      EitherT(F.delay {
        if (Files.exists(file)) ConfigFactoryWrapper.parseFile(file).leftMap[ConfigError](f => ReadConvertError(f))
        else Right(ConfigFactory.empty())
      })
    }

    def fromConfig(config: Config): Either[ConfigError, AppConfig] =
      ConfigSource.fromConfig(config).load[AppConfig].leftMap(ReadConvertError)

    for {
      envFile      <- EitherT.fromEither[F](envFile)
      env          <- loadFileIfExists(envFile)
      postgresFile <- EitherT.fromEither[F](postgresFile)
      postgres     <- loadFileIfExists(postgresFile)
      reference    <- liftT(ConfigFactoryWrapper.defaultReference())
      overrides    <- liftT(ConfigFactoryWrapper.defaultOverrides())
      stack        = overrides withFallback postgres withFallback env withFallback reference
      resolved     = stack.resolve()
      cfg          <- EitherT.fromEither[F](fromConfig(resolved))
    } yield cfg
  }

  implicit final val appConfigConvert: ConfigConvert[AppConfig] =
    deriveConvert[AppConfig]
}
