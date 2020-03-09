package ch.epfl.bluebrain.nexus.cli.config

import java.nio.file.Path

import cats.implicits._
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
    ConfigSource.fromConfig(config).at(prefix).load[A].leftMap(_.head.description)

  /**
    * Attempts to read the passed ''path'' into a [[Config]] and then
    * converts it to the type ''A'' using the pureconfig reader.
    * If the file does not exists, it will use the ''defaultConfig''
    */
  def apply(path: Path, prefix: String, defaultConfig: => Config = emptyConfig): Either[String, A] =
    if (path.toFile.exists())
      Try(ConfigFactory.parseFile(path.toFile)).toEither.leftMap(_.getMessage).flatMap(load(_, prefix))
    else
      load(defaultConfig, prefix)
}

object ConfigReader {
  implicit def configReaderInstance[A: pureconfig.ConfigReader]: ConfigReader[A] = new ConfigReader[A]
}
