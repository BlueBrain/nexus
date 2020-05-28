package ch.epfl.bluebrain.nexus.rdf.derivation

import ch.epfl.bluebrain.nexus.rdf.Iri.AbsoluteIri
import ch.epfl.bluebrain.nexus.rdf.Vocabulary.{nxv, rdf}

final case class Configuration(
    base: AbsoluteIri,
    discriminatorPredicate: AbsoluteIri,
    transformMemberNames: String => String,
    transformConstructorNames: String => String,
    idMemberName: String,
    includeRootConstructorName: Boolean
)

object Configuration {
  val default: Configuration =
    Configuration(nxv.base, rdf.tpe, identity, identity, "id", includeRootConstructorName = true)
}

object defaults {
  implicit final val defaultConfiguration: Configuration = Configuration.default
}
