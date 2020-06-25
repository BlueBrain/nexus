package ch.epfl.bluebrain.nexus.service.config

import java.nio.file.{Path, Paths}

import akka.actor.{ExtendedActorSystem, Extension, ExtensionId, ExtensionIdProvider}
import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.iam.auth.AccessToken
import ch.epfl.bluebrain.nexus.iam.types.Permission
import ch.epfl.bluebrain.nexus.rdf.Iri.AbsoluteIri
import ch.epfl.bluebrain.nexus.rdf.implicits._
import com.typesafe.config.Config
import pureconfig.generic.auto._
import pureconfig.ConvertHelpers.{catchReadError, optF}
import pureconfig.{ConfigConvert, ConfigSource}

import scala.annotation.nowarn

/**
  * Akka settings extension to expose application configuration.  It typically uses the configuration instance of the
  * actor system as the configuration root.
  *
  * @param config the configuration instance to read
  */
@SuppressWarnings(Array("LooksLikeInterpolatedString"))
class Settings(config: Config) extends Extension {

  @nowarn("cat=unused")
  implicit private val uriConverter: ConfigConvert[Uri] =
    ConfigConvert.viaString[Uri](catchReadError(Uri(_)), _.toString)

  @nowarn("cat=unused")
  implicit private val permissionConverter: ConfigConvert[Permission] =
    ConfigConvert.viaString[Permission](optF(Permission(_)), _.toString)

  @nowarn("cat=unused")
  implicit val absoluteIriConverter: ConfigConvert[AbsoluteIri] =
    ConfigConvert.viaString[AbsoluteIri](catchReadError(s => url"$s"), _.toString)

  @nowarn("cat=unused")
  implicit private val pathConverter: ConfigConvert[Path] =
    ConfigConvert.viaString[Path](catchReadError(s => Paths.get(s)), _.toString)

  @nowarn("cat=unused")
  implicit private val authTokenConverter: ConfigConvert[AccessToken] =
    ConfigConvert.viaString[AccessToken](catchReadError(s => AccessToken(s)), _.value)

  val serviceConfig: ServiceConfig =
    ConfigSource.fromConfig(config).at("app").loadOrThrow[ServiceConfig]
}

object Settings extends ExtensionId[Settings] with ExtensionIdProvider {

  override def lookup(): ExtensionId[_ <: Extension] = Settings

  override def createExtension(system: ExtendedActorSystem): Settings = apply(system.settings.config)

  def apply(config: Config): Settings = new Settings(config)
}
