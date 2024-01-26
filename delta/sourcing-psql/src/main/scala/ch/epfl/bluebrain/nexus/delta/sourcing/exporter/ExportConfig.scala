package ch.epfl.bluebrain.nexus.delta.sourcing.exporter

import fs2.io.file.Path
import pureconfig.ConfigConvert.catchReadError
import pureconfig.{ConfigConvert, ConfigReader}
import pureconfig.generic.semiauto.deriveReader

import scala.annotation.nowarn

final case class ExportConfig(permits: Int, target: Path)

object ExportConfig {

  @nowarn("cat=unused")
  implicit final val databaseConfigReader: ConfigReader[ExportConfig] = {
    implicit val pathConverter: ConfigReader[Path] = ConfigConvert.viaString(catchReadError(s => Path(s)), _.toString)
    deriveReader[ExportConfig]
  }

}
