package ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder

import magnolia1.{CaseClass, Magnolia, SealedTrait}

object semiauto {

  type Typeclass[T] = JsonLdDecoder[T]

  def join[T](caseClass: CaseClass[Typeclass, T]): Typeclass[T] =
    MagnoliaJsonLdDecoder.join(caseClass)(Configuration.default)

  def split[T](sealedTrait: SealedTrait[Typeclass, T]): Typeclass[T] =
    MagnoliaJsonLdDecoder.split(sealedTrait)(Configuration.default)

  /**
    * @return
    *   derived json-ld decoder using the default Configuration
    */
  def deriveDefaultJsonLdDecoder[T]: Typeclass[T] = macro Magnolia.gen[T]

}
