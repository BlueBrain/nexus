package ai.senscience.nexus.delta.config

import com.typesafe.config.ConfigOrigin
import pureconfig.error.ConfigReaderFailure

case class InvalidConfiguration(description: String) extends ConfigReaderFailure {
  override def origin: Option[ConfigOrigin] = None
}
