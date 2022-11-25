package ch.epfl.bluebrain.nexus.delta.sourcing.model

import cats.Order
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri

/**
  * Defines a reference to another entity
  *
  * @param project
  *   the project where the entity lives
  * @param id
  *   the id of the referenced entity
  */
final case class EntityDependency private (project: ProjectRef, id: Iri)

object EntityDependency {
  implicit val entityDependencyOrder: Order[EntityDependency] = Order.by { dependency =>
    (dependency.project, dependency.id)
  }
}
