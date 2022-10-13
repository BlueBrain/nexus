package ch.epfl.bluebrain.nexus.delta.sdk.instances

import cats.Order
import ch.epfl.bluebrain.nexus.delta.sourcing.model.EntityDependency

trait EntityDependencyInstances {

  implicit val entityDependencyOrder: Order[EntityDependency] = Order.by { projectRef =>
    (projectRef.project, projectRef.id)
  }

}
