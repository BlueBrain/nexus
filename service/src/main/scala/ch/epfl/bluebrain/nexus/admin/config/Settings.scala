package ch.epfl.bluebrain.nexus.admin.config

import akka.actor.{ExtendedActorSystem, Extension, ExtensionId, ExtensionIdProvider}
import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.rdf.Iri.AbsoluteIri
import ch.epfl.bluebrain.nexus.rdf.implicits._
import com.github.ghik.silencer.silent
import com.typesafe.config.Config
import pureconfig.generic.auto._
import pureconfig.ConvertHelpers.catchReadError
import pureconfig.{ConfigConvert, ConfigSource}

/**
  * Akka settings extension to expose application configuration.  It typically uses the configuration instance of the
  * actor system as the configuration root.
  *
  * @param config the configuration instance to read
  */
@SuppressWarnings(Array("LooksLikeInterpolatedString"))
class Settings(config: Config) extends Extension {

  @silent // implicit val definitions are not recognized as being used
  val appConfig: AppConfig = {
    implicit val uriConverter: ConfigConvert[Uri] =
      ConfigConvert.viaString[Uri](catchReadError(s => Uri(s)), _.toString)
    implicit val absoluteIriConverter: ConfigConvert[AbsoluteIri] =
      ConfigConvert.viaString[AbsoluteIri](catchReadError(s => url"$s"), _.toString)
    ConfigSource.fromConfig(config).at("app").loadOrThrow[AppConfig]
  }
}

object Settings extends ExtensionId[Settings] with ExtensionIdProvider {

  override def lookup(): ExtensionId[_ <: Extension] = Settings

  override def createExtension(system: ExtendedActorSystem): Settings = apply(system.settings.config)

  def apply(config: Config): Settings = new Settings(config)
}
