package ch.epfl.bluebrain.nexus.delta.plugins.archive.model

import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.configuration.semiauto.deriveConfigJsonLdDecoder
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.{Configuration, JsonLdDecoder}
import ch.epfl.bluebrain.nexus.delta.sdk.model.NonEmptySet

import scala.annotation.nowarn

/**
  * An archive value.
  *
  * @param resources the collection of referenced resources
  */
final case class ArchiveValue(resources: NonEmptySet[ArchiveReference])

object ArchiveValue {

  @nowarn("cat=unused")
  implicit final val archiveValueJsonLdDecoder: JsonLdDecoder[ArchiveValue] = {
    implicit val cfg: Configuration = Configuration.default
    deriveConfigJsonLdDecoder[ArchiveValue]
  }
}
