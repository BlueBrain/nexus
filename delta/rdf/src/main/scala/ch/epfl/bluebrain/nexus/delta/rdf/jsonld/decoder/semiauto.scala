package ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder

import magnolia.{CaseClass, Magnolia, SealedTrait}

object semiauto {

  type Typeclass[T] = JsonLdDecoder[T]

  def combine[T](caseClass: CaseClass[Typeclass, T]): Typeclass[T] =
    MagnoliaJsonLdDecoder.combine(caseClass)(Configuration.default)

  def dispatch[T](sealedTrait: SealedTrait[Typeclass, T]): Typeclass[T] =
    MagnoliaJsonLdDecoder.dispatch(sealedTrait)(Configuration.default)

  /**
    * @return
    *   derived json-ld decoder using the default Configuration
    */
  def deriveDefaultJsonLdDecoder[T]: Typeclass[T] = macro Magnolia.gen[T]

}
