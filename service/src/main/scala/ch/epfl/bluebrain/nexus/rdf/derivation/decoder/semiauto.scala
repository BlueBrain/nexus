package ch.epfl.bluebrain.nexus.rdf.derivation.decoder

import ch.epfl.bluebrain.nexus.rdf.GraphDecoder
import ch.epfl.bluebrain.nexus.rdf.derivation.{Configuration, MagnoliaGraphDecoder}
import magnolia.{CaseClass, Magnolia, SealedTrait}

object semiauto {

  type Typeclass[T] = GraphDecoder[T]

  def combine[T](caseClass: CaseClass[Typeclass, T]): Typeclass[T] =
    MagnoliaGraphDecoder.combine(caseClass)(Configuration.default)

  def dispatch[T](sealedTrait: SealedTrait[Typeclass, T]): Typeclass[T] =
    MagnoliaGraphDecoder.dispatch(sealedTrait)(Configuration.default)

  def deriveGraphDecoder[T]: Typeclass[T] = macro Magnolia.gen[T]
}
