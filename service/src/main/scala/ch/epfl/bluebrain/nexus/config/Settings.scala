package ch.epfl.bluebrain.nexus.config

import akka.actor.{ExtendedActorSystem, Extension, ExtensionId, ExtensionIdProvider}
import ch.epfl.bluebrain.nexus.utils.Codecs
import com.typesafe.config.Config
import pureconfig._
import pureconfig.generic.auto._

/**
  * Akka settings extension to expose application configuration.  It typically uses the configuration instance of the
  * actor system as the configuration root.
  *
  * @param config the configuration instance to read
  */
@SuppressWarnings(Array("LooksLikeInterpolatedString"))
class Settings(config: Config) extends Extension with Codecs {
  val appConfig: AppConfig =
    ConfigSource.fromConfig(config).at("app").loadOrThrow[AppConfig]
}

object Settings extends ExtensionId[Settings] with ExtensionIdProvider {

  override def lookup(): ExtensionId[_ <: Extension] = Settings

  override def createExtension(system: ExtendedActorSystem): Settings = apply(system.settings.config)

  final def apply(config: Config): Settings = new Settings(config)
}
