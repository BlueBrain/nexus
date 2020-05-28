package ch.epfl.bluebrain.nexus.rdf.derivation.encoder

import ch.epfl.bluebrain.nexus.rdf.GraphEncoder
import ch.epfl.bluebrain.nexus.rdf.derivation.{Configuration, MagnoliaGraphEncoder}
import magnolia._

object semiauto {

  type Typeclass[T] = GraphEncoder[T]

  def combine[T](caseClass: CaseClass[Typeclass, T]): Typeclass[T] =
    MagnoliaGraphEncoder.combine(caseClass)(Configuration.default)

  def dispatch[T](sealedTrait: SealedTrait[Typeclass, T]): Typeclass[T] =
    MagnoliaGraphEncoder.dispatch(sealedTrait)(Configuration.default)

  def deriveGraphEncoder[T]: Typeclass[T] = macro Magnolia.gen[T]
}
