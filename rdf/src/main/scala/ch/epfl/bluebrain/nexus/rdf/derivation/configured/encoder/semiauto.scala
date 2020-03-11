package ch.epfl.bluebrain.nexus.rdf.derivation.configured.encoder

import ch.epfl.bluebrain.nexus.rdf.derivation.MagnoliaGraphEncoder
import ch.epfl.bluebrain.nexus.rdf.derivation.configured.Configuration
import ch.epfl.bluebrain.nexus.rdf.graph.GraphEncoder
import magnolia.{CaseClass, Magnolia, SealedTrait}

object semiauto {

  type Typeclass[T] = GraphEncoder[T]

  def combine[T](caseClass: CaseClass[Typeclass, T])(implicit config: Configuration): Typeclass[T] =
    MagnoliaGraphEncoder.combine(caseClass)

  def dispatch[T](sealedTrait: SealedTrait[Typeclass, T])(implicit config: Configuration): Typeclass[T] =
    MagnoliaGraphEncoder.dispatch(sealedTrait)

  def deriveConfiguredGraphEncoder[T]: Typeclass[T] = macro Magnolia.gen[T]
}
