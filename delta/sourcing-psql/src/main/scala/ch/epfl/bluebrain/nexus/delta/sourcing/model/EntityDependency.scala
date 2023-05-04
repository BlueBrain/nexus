package ch.epfl.bluebrain.nexus.delta.sourcing.model

import cats.Order
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri

/**
  * Defines a dependency to another entity
  */
object EntityDependency {

  /**
    * Defines an entity that is referenced by a project or entity
    */
  final case class ReferencedBy(project: ProjectRef, id: Iri)

  /**
    * Defines an entity that a project / entity depends on
    */
  final case class DependsOn(project: ProjectRef, id: Iri)

  object DependsOn {
    implicit val dependsOnOrder: Order[DependsOn] = Order.by { dependency =>
      (dependency.project, dependency.id)
    }
  }
}
