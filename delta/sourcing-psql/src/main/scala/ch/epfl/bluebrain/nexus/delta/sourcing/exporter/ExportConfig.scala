package ch.epfl.bluebrain.nexus.delta.sourcing.exporter

import fs2.io.file.Path
import pureconfig.ConfigConvert.catchReadError
import pureconfig.generic.semiauto.deriveReader
import pureconfig.{ConfigConvert, ConfigReader}

final case class ExportConfig(batchSize: Int, limitPerFile: Int, permits: Int, target: Path)

object ExportConfig {

  implicit final val databaseConfigReader: ConfigReader[ExportConfig] = {
    implicit val pathConverter: ConfigReader[Path] = ConfigConvert.viaString(catchReadError(s => Path(s)), _.toString)
    deriveReader[ExportConfig]
  }

}
