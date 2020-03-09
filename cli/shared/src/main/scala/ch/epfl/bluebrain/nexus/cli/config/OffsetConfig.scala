package ch.epfl.bluebrain.nexus.cli.config

import java.nio.file.{Path, Paths}

import ch.epfl.bluebrain.nexus.cli.config.OffsetConfig._
import ch.epfl.bluebrain.nexus.cli.types.Offset
import pureconfig.ConfigConvert
import pureconfig.error.CannotConvert
import pureconfig.generic.semiauto.deriveConvert

/**
  * It contains the offset value of the latest consumed eventId
  */
final case class OffsetConfig(value: Option[Offset]) {

  /**
    * Writes the current config to the passed ''path'' location. If the path file already exists, it overrides its content.
    */
  def write[F[_]](path: Path = defaultPath)(implicit writer: ConfigWriter[OffsetConfig, F]): F[Either[String, Unit]] =
    writer(this, path, prefix)

}

object OffsetConfig {
  private[cli] val defaultPath = Paths.get(System.getProperty("user.home"), ".nexus", "offset.conf")
  private[cli] val prefix      = "offset"

  /**
    * Attempts to construct an Offset configuration from the passed path. If the path is not provided,
    * the default path ~/.nexus/offset.conf will be used.
    *
    */
  def apply(path: Path = defaultPath)(implicit reader: ConfigReader[OffsetConfig]): Either[String, OffsetConfig] =
    reader(path, prefix)

  implicit private[cli] val offsetValueConfigConverter: ConfigConvert[Offset] =
    ConfigConvert.viaNonEmptyString(
      string => Offset(string).toRight(CannotConvert(string, "Offset", "value must be a TimeBased UUID or a Long.")),
      _.asString
    )

  implicit val offsetConfigConvert: ConfigConvert[OffsetConfig] =
    deriveConvert[OffsetConfig]
}
