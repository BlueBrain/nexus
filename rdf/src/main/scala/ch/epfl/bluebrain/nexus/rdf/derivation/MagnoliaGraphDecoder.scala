package ch.epfl.bluebrain.nexus.rdf.derivation

import ch.epfl.bluebrain.nexus.rdf.Node.IriNode
import ch.epfl.bluebrain.nexus.rdf.derivation.DerivationError.DuplicatedParameters
import ch.epfl.bluebrain.nexus.rdf.derivation.configured.Configuration
import ch.epfl.bluebrain.nexus.rdf.graph.GraphDecoder.Result
import ch.epfl.bluebrain.nexus.rdf.graph.{Cursor, DecodingError, GraphDecoder}
import magnolia.{CaseClass, SealedTrait}

private[derivation] object MagnoliaGraphDecoder {

  def combine[A](caseClass: CaseClass[GraphDecoder, A])(implicit config: Configuration): GraphDecoder[A] = {
    val paramPredicateLookup = predicateResolution(caseClass)

    if (paramPredicateLookup.values.toVector.distinct.size != caseClass.parameters.length) throw DuplicatedParameters

    new GraphDecoder[A] {
      override def apply(cursor: Cursor): Result[A] =
        caseClass.constructMonadic { p =>
          if (p.label == config.idMemberName) p.typeclass.apply(cursor)
          else {
            val next = cursor.downSet(paramPredicateLookup(p.label))
            p.typeclass.apply(next)
          }
        }
    }
  }

  def dispatch[A](sealedTrait: SealedTrait[GraphDecoder, A])(implicit config: Configuration): GraphDecoder[A] =
    new GraphDecoder[A] {
      override def apply(c: Cursor): Result[A] = {
        c.downSet(IriNode(config.discriminatorPredicate)).values match {
          case Some(values) =>
            sealedTrait.subtypes.find(st => config.iriNodeFromBase(st.typeName.short).exists(values.contains)) match {
              case Some(st) => st.typeclass.apply(c)
              case None =>
                Left(DecodingError(s"Unable to find type discriminator for ${sealedTrait.typeName.short}", c.history))
            }
          case None =>
            Left(DecodingError(s"Unable to find type discriminator for ${sealedTrait.typeName.short}", c.history))
        }
      }
    }
}
