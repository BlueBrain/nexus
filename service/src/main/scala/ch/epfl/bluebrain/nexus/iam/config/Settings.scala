package ch.epfl.bluebrain.nexus.iam.config

import akka.actor.{ExtendedActorSystem, Extension, ExtensionId, ExtensionIdProvider}
import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.iam.types.Permission
import com.github.ghik.silencer.silent
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
@SuppressWarnings(Array("LooksLikeInterpolatedString"))
class Settings(config: Config) extends Extension {

  @silent // not recognized as used... but it is below
  implicit private val uriConverter: ConfigConvert[Uri] =
    ConfigConvert.viaString[Uri](catchReadError(Uri(_)), _.toString)

  @silent // not recognized as used... but it is below
  implicit private val permissionConverter: ConfigConvert[Permission] =
    ConfigConvert.viaString[Permission](optF(Permission(_)), _.toString)

  val appConfig: AppConfig =
    ConfigSource.fromConfig(config).at("app").loadOrThrow[AppConfig]
}

object Settings extends ExtensionId[Settings] with ExtensionIdProvider {

  override def lookup(): ExtensionId[_ <: Extension] = Settings

  override def createExtension(system: ExtendedActorSystem): Settings = apply(system.settings.config)

  def apply(config: Config): Settings = new Settings(config)
}
