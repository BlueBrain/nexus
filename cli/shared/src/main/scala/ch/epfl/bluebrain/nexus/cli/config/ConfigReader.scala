package ch.epfl.bluebrain.nexus.cli.config

import java.nio.file.Path

import cats.implicits._
import ch.epfl.bluebrain.nexus.cli.error.ConfigError
import ch.epfl.bluebrain.nexus.cli.error.ConfigError.{ReadConvertError, ReadError}
import com.typesafe.config.ConfigFactory.parseFile
import com.typesafe.config.{Config, ConfigFactory}
import pureconfig.ConfigSource

import scala.util.Try

/**
  * A config reader. The pipeline is as follows: file -> (typesafe)Config -> A
  *
  * @tparam A the configuration result type
  */
class ConfigReader[A: pureconfig.ConfigReader] {

  private val emptyConfig = ConfigFactory.empty().root().toConfig

  private def load(config: Config, prefix: String) =
    ConfigSource.fromConfig(config).at(prefix).load[A].leftMap(ReadConvertError)

  /**
    * Attempts to read the passed ''path'' into a [[Config]] and then
    * converts it to the type ''A'' using the pureconfig reader.
    * If the file does not exists, it will use the ''defaultConfig''
    */
  def apply(path: Path, prefix: String, defaultConfig: => Config = emptyConfig): Either[ConfigError, A] =
    if (path.toFile.exists())
      Try(parseFile(path.toFile)).toEither.leftMap(err => ReadError(path, err.getMessage)).flatMap(load(_, prefix))
    else
      load(defaultConfig, prefix)
}

object ConfigReader {
  implicit def configReaderInstance[A: pureconfig.ConfigReader]: ConfigReader[A] = new ConfigReader[A]
}
