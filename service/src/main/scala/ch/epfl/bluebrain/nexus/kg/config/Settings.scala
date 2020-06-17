package ch.epfl.bluebrain.nexus.kg.config

import java.nio.file.{Path, Paths}

import akka.actor.{ExtendedActorSystem, Extension, ExtensionId, ExtensionIdProvider}
import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.iam.client.types.{AuthToken, Permission}
import ch.epfl.bluebrain.nexus.rdf.implicits._
import ch.epfl.bluebrain.nexus.rdf.Iri.AbsoluteIri
import scala.annotation.nowarn
import com.typesafe.config.Config
import pureconfig.generic.auto._
import pureconfig.ConvertHelpers._
import pureconfig._

/**
  * Akka settings extension to expose application configuration.  It typically uses the configuration instance of the
  * actor system as the configuration root.
  *
  * @param config the configuration instance to read
  */
@SuppressWarnings(Array("LooksLikeInterpolatedString", "OptionGet"))
@nowarn("cat=unused") // private implicits in automatic derivation are not recognized as used
class Settings(config: Config) extends Extension {

  implicit private val uriConverter: ConfigConvert[Uri] =
    ConfigConvert.viaString[Uri](catchReadError(s => Uri(s)), _.toString)

  implicit private val authTokenConverter: ConfigConvert[AuthToken] =
    ConfigConvert.viaString[AuthToken](catchReadError(s => AuthToken(s)), _.value)

  implicit private val permissionConverter: ConfigConvert[Permission] =
    ConfigConvert.viaString[Permission](optF(Permission.apply), _.value)

  implicit private val pathConverter: ConfigConvert[Path] =
    ConfigConvert.viaString[Path](catchReadError(s => Paths.get(s)), _.toString)

  implicit private val absoluteIriConverter: ConfigConvert[AbsoluteIri] =
    ConfigConvert.viaString[AbsoluteIri](catchReadError(s => url"$s"), _.toString)

  val appConfig: AppConfig = ConfigSource.fromConfig(config).at("app").loadOrThrow[AppConfig]

}

object Settings extends ExtensionId[Settings] with ExtensionIdProvider {

  override def lookup(): ExtensionId[_ <: Extension] = Settings

  override def createExtension(system: ExtendedActorSystem): Settings = apply(system.settings.config)

  def apply(config: Config): Settings = new Settings(config)
}
