package ai.senscience.nexus.tests.config

import akka.http.scaladsl.model.Uri
import com.typesafe.config.Config
import pureconfig.ConvertHelpers.catchReadError
import pureconfig.generic.ExportMacros
import pureconfig.{ConfigConvert, ConfigReader, ConfigSource, Exported}

import scala.reflect.ClassTag

trait ConfigLoader {

  implicit def exportReader[A]: Exported[ConfigReader[A]] = macro ExportMacros.exportDerivedReader[A]

  implicit val uriConverter: ConfigConvert[Uri] =
    ConfigConvert.viaString[Uri](catchReadError(s => Uri(s)), _.toString)

  def load[A: ClassTag](config: Config, namespace: String)(implicit reader: ConfigReader[A]): A =
    ConfigSource.fromConfig(config).at(namespace).loadOrThrow[A]

}

object ConfigLoader extends ConfigLoader
