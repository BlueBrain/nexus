package ch.epfl.bluebrain.nexus.cli.config.influx

import java.nio.file.Path

import ch.epfl.bluebrain.nexus.cli.config.PrintConfig
import ch.epfl.bluebrain.nexus.cli.sse.{OrgLabel, ProjectLabel}
import ch.epfl.bluebrain.nexus.cli.utils.Codecs._
import org.http4s.Uri
import pureconfig.configurable.{genericMapReader, genericMapWriter}
import pureconfig.error.CannotConvert
import pureconfig.generic.semiauto._
import pureconfig.{ConfigConvert, ConfigReader, ConfigWriter}

import scala.annotation.nowarn
import scala.concurrent.duration.FiniteDuration

/**
  * influxDB connectivity information along with the projection configuration.
  *
  * @param endpoint           the influxDB v1.x API endpoint
  * @param database           the database to be used
  * @param dbCreationCommand  the command used to create an influxDB database
  * @param offsetFile         the location where the influxDB projection offset should be read / stored
  * @param offsetSaveInterval how frequent to save the stream offset into the offset file
  * @param print              the configuration for printing output to the client
  * @param projects           the project to config mapping
  */
final case class InfluxConfig(
    endpoint: Uri,
    database: String,
    dbCreationCommand: String,
    offsetFile: Path,
    offsetSaveInterval: FiniteDuration,
    print: PrintConfig,
    projects: Map[(OrgLabel, ProjectLabel), ProjectConfig]
)

object InfluxConfig {

  @nowarn("cat=unused")
  implicit final val influxConfigConvert: ConfigConvert[InfluxConfig] = {
    implicit val labelTupleMapReader: ConfigReader[Map[(OrgLabel, ProjectLabel), ProjectConfig]] =
      genericMapReader[(OrgLabel, ProjectLabel), ProjectConfig] { key =>
        key.split('/') match {
          case Array(org, proj) if !org.isBlank && !proj.isBlank => Right(OrgLabel(org.trim) -> ProjectLabel(proj.trim))
          case _                                                 => Left(CannotConvert(key, "project reference", "the format does not match {org}/{project}"))
        }
      }
    implicit val labelTupleMapWriter: ConfigWriter[Map[(OrgLabel, ProjectLabel), ProjectConfig]] =
      genericMapWriter { case (OrgLabel(org), ProjectLabel(proj)) => s"$org/$proj" }
    deriveConvert[InfluxConfig]
  }
}
