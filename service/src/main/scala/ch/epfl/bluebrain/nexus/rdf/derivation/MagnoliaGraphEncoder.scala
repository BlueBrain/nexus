package ch.epfl.bluebrain.nexus.rdf.derivation

import ch.epfl.bluebrain.nexus.rdf.Node.{BNode, IriNode, IriOrBNode}
import ch.epfl.bluebrain.nexus.rdf.{Graph, GraphEncoder}
import magnolia.{CaseClass, SealedTrait}

private[derivation] object MagnoliaGraphEncoder {

  def combine[A](caseClass: CaseClass[GraphEncoder, A])(implicit config: Configuration): GraphEncoder[A] = {
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

    val paramIdLookup = caseClass.parameters.find(_.label == config.idMemberName)
    paramIdLookup match {
      case Some(id) =>
        val rest = caseClass.parameters.filterNot(_ == id)
        new GraphEncoder[A] {
          override def apply(a: A): Graph = {
            rest.foldLeft(id.typeclass.apply(id.dereference(a))) {
              case (g, field) =>
                val predicate  = paramPredicateLookup(field.label)
                val fieldGraph = field.typeclass.apply(field.dereference(a))
                g.append(predicate, fieldGraph)
            }
          }
        }
      case None =>
        new GraphEncoder[A] {
          override def apply(a: A): Graph = {
            caseClass.parameters.foldLeft(Graph(BNode())) {
              case (g, field) =>
                val predicate  = paramPredicateLookup(field.label)
                val fieldGraph = field.typeclass.apply(field.dereference(a))
                g.append(predicate, fieldGraph)
            }
          }
        }
    }
  }

  def dispatch[A](sealedTrait: SealedTrait[GraphEncoder, A])(implicit config: Configuration): GraphEncoder[A] =
    new GraphEncoder[A] {
      override def apply(a: A): Graph =
        sealedTrait.dispatch(a) { subType =>
          val g = subType.typeclass.apply(subType.cast(a))
          g.root match {
            case ibn: IriOrBNode =>
              val discriminatorTriple =
                (ibn, IriNode(config.discriminatorPredicate), IriNode(config.base + subType.typeName.short))
              if (config.includeRootConstructorName) {
                val rootTriple =
                  (ibn, IriNode(config.discriminatorPredicate), IriNode(config.base + sealedTrait.typeName.short))
                g + discriminatorTriple + rootTriple
              } else g + discriminatorTriple
            case _ => g
          }
        }
    }
}
