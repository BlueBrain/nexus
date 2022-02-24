package ch.epfl.bluebrain.nexus.delta.plugins.jira.config

import akka.http.scaladsl.model.Uri
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.Secret
import com.typesafe.config.Config
import pureconfig.{ConfigReader, ConfigSource}
import pureconfig.generic.auto._
import pureconfig.error.CannotConvert
import pureconfig.generic.semiauto.deriveReader

import scala.annotation.nowarn
import scala.util.Try

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

  @nowarn("cat=unused")
  implicit private val uriConfigReader: ConfigReader[Uri] = ConfigReader.fromString(str =>
    Try(Uri(str))
      .filter(_.isAbsolute)
      .toEither
      .leftMap(err => CannotConvert(str, classOf[Uri].getSimpleName, err.getMessage))
  )

  implicit final val jiraConfigReader: ConfigReader[JiraConfig] =
    deriveReader[JiraConfig]

}
