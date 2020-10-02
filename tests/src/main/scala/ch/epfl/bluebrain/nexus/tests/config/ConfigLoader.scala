package ch.epfl.bluebrain.nexus.tests.config

import akka.http.scaladsl.model.Uri
import com.typesafe.config.Config
import pureconfig.ConvertHelpers.catchReadError
import pureconfig.{ConfigConvert, ConfigReader, ConfigSource, Derivation, Exported}
import pureconfig.generic.ExportMacros

import scala.reflect.ClassTag

trait ConfigLoader {

  implicit def exportReader[A]: Exported[ConfigReader[A]] = macro ExportMacros.exportDerivedReader[A]

  implicit val uriConverter: ConfigConvert[Uri] =
    ConfigConvert.viaString[Uri](catchReadError(s => Uri(s)), _.toString)

  def load[A: ClassTag](config: Config, namespace: String)(implicit reader: Derivation[ConfigReader[A]]): A =
    ConfigSource.fromConfig(config).at(namespace).loadOrThrow[A]

}

object ConfigLoader extends ConfigLoader
