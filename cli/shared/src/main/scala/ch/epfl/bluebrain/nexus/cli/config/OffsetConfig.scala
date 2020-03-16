package ch.epfl.bluebrain.nexus.cli.config

import java.nio.file.{Path, Paths}

import cats.Monad
import cats.data.EitherT
import ch.epfl.bluebrain.nexus.cli.config.OffsetConfig._
import ch.epfl.bluebrain.nexus.cli.error.ConfigError
import ch.epfl.bluebrain.nexus.cli.error.ConfigError.UserHomeNotDefined
import ch.epfl.bluebrain.nexus.cli.types.Offset
import pureconfig.ConfigConvert
import pureconfig.error.CannotConvert
import pureconfig.generic.semiauto.deriveConvert

import scala.util.Try

/**
  * It contains the offset value of the latest consumed eventId
  */
final case class OffsetConfig(value: Option[Offset]) { self =>

  /**
    * Writes the current config to the default path ~/.nexus/offset.conf location.
    * If the path file already exists, it overrides its content.
    */
  def write[F[_]]()(implicit writer: ConfigWriter[OffsetConfig, F], F: Monad[F]): F[Either[ConfigError, Unit]] =
    EitherT.fromEither[F](defaultPath).flatMap(path => EitherT(write(path))).value

  /**
    * Writes the current config to the passed ''path'' location.
    * If the path file already exists, it overrides its content.
    */
  def write[F[_]](path: Path)(implicit writer: ConfigWriter[OffsetConfig, F]): F[Either[ConfigError, Unit]] =
    writer(self, path, prefix)

}

object OffsetConfig {
  private[cli] def defaultPath: Either[ConfigError, Path] =
    Try(System.getProperty("user.home"))
      .fold(_ => Left(UserHomeNotDefined), home => Right(Paths.get(home, ".nexus", "offset.conf")))
  private[cli] val prefix = "offset"

  /**
    * Attempts to construct an Offset configuration from the passed default path ~/.nexus/offset.conf.
    */
  def apply()(implicit reader: ConfigReader[OffsetConfig]): Either[ConfigError, OffsetConfig] =
    defaultPath.flatMap(path => reader(path, prefix))

  /**
    * Attempts to construct an Offset configuration from the passed path. If the path is not provided,
    * the default path ~/.nexus/offset.conf will be used.
    *
    */
  def apply(path: Path)(implicit reader: ConfigReader[OffsetConfig]): Either[ConfigError, OffsetConfig] =
    reader(path, prefix)

  implicit private[cli] val offsetValueConfigConverter: ConfigConvert[Offset] =
    ConfigConvert.viaNonEmptyString(
      string => Offset(string).toRight(CannotConvert(string, "Offset", "value must be a TimeBased UUID or a Long.")),
      _.asString
    )

  implicit val offsetConfigConvert: ConfigConvert[OffsetConfig] =
    deriveConvert[OffsetConfig]
}
