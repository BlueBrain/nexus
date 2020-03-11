package ch.epfl.bluebrain.nexus.rdf.derivation.configured.decoder

import ch.epfl.bluebrain.nexus.rdf.derivation.MagnoliaGraphDecoder
import ch.epfl.bluebrain.nexus.rdf.derivation.configured.Configuration
import ch.epfl.bluebrain.nexus.rdf.graph.GraphDecoder
import magnolia.{CaseClass, Magnolia, SealedTrait}

object semiauto {

  type Typeclass[T] = GraphDecoder[T]

  def combine[T](caseClass: CaseClass[Typeclass, T])(implicit config: Configuration): Typeclass[T] =
    MagnoliaGraphDecoder.combine(caseClass)

  def dispatch[T](sealedTrait: SealedTrait[Typeclass, T])(implicit config: Configuration): Typeclass[T] =
    MagnoliaGraphDecoder.dispatch(sealedTrait)

  def deriveConfiguredGraphDecoder[T]: Typeclass[T] = macro Magnolia.gen[T]
}
