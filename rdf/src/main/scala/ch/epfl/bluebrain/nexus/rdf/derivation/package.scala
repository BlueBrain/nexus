package ch.epfl.bluebrain.nexus.rdf

import ch.epfl.bluebrain.nexus.rdf.Node.IriNode
import ch.epfl.bluebrain.nexus.rdf.derivation.DerivationError.InvalidUriTransformation
import ch.epfl.bluebrain.nexus.rdf.derivation.configured.Configuration
import magnolia.CaseClass

package object derivation {

  /**
    * Attempts to create a map which transforms the passed ''caseClass'' parameters to Uri predicates
    * @throws DerivationError when a parameter cannot be converted to a Uri
    */
  def predicateResolution[A, T[_]](
      caseClass: CaseClass[T, A]
  )(implicit config: Configuration): Map[String, IriNode] =
    caseClass.parameters.map { p =>
      val idAnnotation = p.annotations.collectFirst {
        case ann: id => ann
      }
      idAnnotation match {
        case Some(ann) => p.label -> IriNode(ann.value)
        case None =>
          config.predicate(p.label) match {
            case Left(attemptedUriString) => throw InvalidUriTransformation(p.label, attemptedUriString)
            case Right(iriNode)           => p.label -> iriNode
          }
      }
    }.toMap

}
