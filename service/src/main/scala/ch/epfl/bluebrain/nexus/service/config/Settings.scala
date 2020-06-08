package ch.epfl.bluebrain.nexus.service.config

import akka.actor.{ExtendedActorSystem, Extension, ExtensionId, ExtensionIdProvider}
import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.iam.types.Permission
import ch.epfl.bluebrain.nexus.rdf.Iri.AbsoluteIri
import ch.epfl.bluebrain.nexus.rdf.implicits._
import com.github.ghik.silencer.silent
import com.typesafe.config.Config
import pureconfig.generic.auto._
import pureconfig.ConvertHelpers.{catchReadError, optF}
import pureconfig.{ConfigConvert, ConfigSource}

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

  @silent // not recognized as used... but it is below
  implicit val absoluteIriConverter: ConfigConvert[AbsoluteIri] =
    ConfigConvert.viaString[AbsoluteIri](catchReadError(s => url"$s"), _.toString)

  val serviceConfig: ServiceConfig =
    ConfigSource.fromConfig(config).at("app").loadOrThrow[ServiceConfig]
}

object Settings extends ExtensionId[Settings] with ExtensionIdProvider {

  override def lookup(): ExtensionId[_ <: Extension] = Settings

  override def createExtension(system: ExtendedActorSystem): Settings = apply(system.settings.config)

  def apply(config: Config): Settings = new Settings(config)
}
