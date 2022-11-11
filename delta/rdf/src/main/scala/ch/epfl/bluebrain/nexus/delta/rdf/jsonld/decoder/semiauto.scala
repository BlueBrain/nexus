package ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder

import magnolia.{CaseClass, Magnolia, SealedTrait}

object semiauto {

  type Typeclass[T] = JsonLdDecoder[T]

  def combine[T](caseClass: CaseClass[Typeclass, T])(implicit configuration: Configuration): Typeclass[T] =
    MagnoliaJsonLdDecoder.combine(caseClass)

  def dispatch[T](sealedTrait: SealedTrait[Typeclass, T])(implicit configuration: Configuration): Typeclass[T] =
    MagnoliaJsonLdDecoder.dispatch(sealedTrait)

  def deriveJsonLdDecoder[T]: Typeclass[T] = macro Magnolia.gen[T]

}
