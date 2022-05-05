package ch.epfl.bluebrain.nexus.delta.plugins.jira.config

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.sdk.instances._
import ch.epfl.bluebrain.nexus.delta.kernel.Secret
import com.typesafe.config.Config
import pureconfig.generic.semiauto.deriveReader
import pureconfig.{ConfigReader, ConfigSource}

/**
  * Jira plugin configuration
  * @param base
  *   the base url of Jira
  * @param consumerKey
  *   the consumer key for the OAuth configuration
  * @param secret
  *   the secret for the OAuth configuration
  * @param privateKey
  *   the private key to sign messages to Jira
  */
final case class JiraConfig(
    base: Uri,
    consumerKey: String,
    secret: Secret[String],
    privateKey: Secret[String]
)

object JiraConfig {

  def load(config: Config): JiraConfig =
    ConfigSource
      .fromConfig(config)
      .at("plugins.jira")
      .loadOrThrow[JiraConfig]

  implicit final val jiraConfigReader: ConfigReader[JiraConfig] =
    deriveReader[JiraConfig]

}
