package ch.epfl.bluebrain.nexus.rdf.derivation

import ch.epfl.bluebrain.nexus.rdf.Node.{BNode, IriNode, IriOrBNode}
import ch.epfl.bluebrain.nexus.rdf.derivation.DerivationError.DuplicatedParameters
import ch.epfl.bluebrain.nexus.rdf.derivation.configured.Configuration
import ch.epfl.bluebrain.nexus.rdf.graph.Graph.Triple
import ch.epfl.bluebrain.nexus.rdf.graph.{Graph, GraphEncoder}
import magnolia.{CaseClass, SealedTrait}

private[derivation] object MagnoliaGraphEncoder {

  def combine[A](caseClass: CaseClass[GraphEncoder, A])(implicit config: Configuration): GraphEncoder[A] = {

    val paramPredicateLookup = predicateResolution(caseClass)

    if (paramPredicateLookup.values.toVector.distinct.size != caseClass.parameters.length) throw DuplicatedParameters

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
              val discriminatorTriple = config.iriNodeFromBase(subType.typeName.short).map { iriNodeObj =>
                (ibn, IriNode(config.discriminatorPredicate), iriNodeObj)
              }
              if (config.includeRootConstructorName) {
                val rootTriple = config.iriNodeFromBase(sealedTrait.typeName.short).map { iriNodeObj =>
                  (ibn, IriNode(config.discriminatorPredicate), iriNodeObj)
                }
                val discriminators = Set.empty[Triple] ++ discriminatorTriple.toOption ++ rootTriple.toOption
                g ++ discriminators
              } else {
                g ++ discriminatorTriple.toOption.toSet[Triple]
              }
            case _ => g
          }
        }
    }
}
