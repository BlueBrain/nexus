package ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.configuration

import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.{Configuration, JsonLdDecoder, MagnoliaJsonLdDecoder}
import magnolia1.{CaseClass, Magnolia, SealedTrait}

object semiauto {

  type Typeclass[T] = JsonLdDecoder[T]

  def join[T](caseClass: CaseClass[Typeclass, T])(implicit config: Configuration): Typeclass[T] =
    MagnoliaJsonLdDecoder.join(caseClass)

  def split[T](sealedTrait: SealedTrait[Typeclass, T])(implicit config: Configuration): Typeclass[T] =
    MagnoliaJsonLdDecoder.split(sealedTrait)

  /**
    * @return
    *   derived json-ld decoder using a provided config (implicit)
    */
  def deriveConfigJsonLdDecoder[T]: Typeclass[T] = macro Magnolia.gen[T]
}
