package ch.epfl.bluebrain.nexus.rdf.derivation

import ch.epfl.bluebrain.nexus.rdf.GraphDecoder.Result
import ch.epfl.bluebrain.nexus.rdf.Node.IriNode
import ch.epfl.bluebrain.nexus.rdf.{Cursor, DecodingError, GraphDecoder}
import magnolia.{CaseClass, SealedTrait}

private[derivation] object MagnoliaGraphDecoder {

  def combine[A](caseClass: CaseClass[GraphDecoder, A])(implicit config: Configuration): GraphDecoder[A] = {
    val paramPredicateLookup = caseClass.parameters.map { p =>
      val idAnnotation = p.annotations.collectFirst {
        case ann: id => ann
      }
      idAnnotation match {
        case Some(ann) => p.label -> IriNode(ann.value)
        case None      => p.label -> IriNode(config.base + config.transformMemberNames(p.label))
      }
    }.toMap

    if (paramPredicateLookup.values.toList.distinct.size != caseClass.parameters.length) {
      throw DerivationError("Duplicate key detected after applying transformation function for case class parameters")
    }

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
            sealedTrait.subtypes.find(st => values.contains(IriNode(config.base + st.typeName.short))) match {
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
