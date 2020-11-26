package ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.configuration

import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.{Configuration, JsonLdDecoder, MagnoliaJsonLdDecoder}
import magnolia.{CaseClass, Magnolia, SealedTrait}

object semiauto {

  type Typeclass[T] = JsonLdDecoder[T]

  def combine[T](caseClass: CaseClass[Typeclass, T])(implicit config: Configuration): Typeclass[T] =
    MagnoliaJsonLdDecoder.combine(caseClass)

  def dispatch[T](sealedTrait: SealedTrait[Typeclass, T])(implicit config: Configuration): Typeclass[T] =
    MagnoliaJsonLdDecoder.dispatch(sealedTrait)

  def deriveConfigJsonLdDecoder[T]: Typeclass[T] = macro Magnolia.gen[T]
}
