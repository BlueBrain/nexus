package ch.epfl.bluebrain.nexus.ship.config

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import pureconfig.ConfigReader
import pureconfig.generic.semiauto.deriveReader

final case class IriPatcherConfig(enabled: Boolean, originalPrefix: Iri, targetPrefix: Iri)

object IriPatcherConfig {
  implicit final val iriPatcherReader: ConfigReader[IriPatcherConfig] =
    deriveReader[IriPatcherConfig]
}
