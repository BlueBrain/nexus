package ch.epfl.bluebrain.nexus.delta.provisioning

import cats.syntax.all.*
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.sdk.ProvisioningAction

trait ProvisioningCoordinator

object ProvisioningCoordinator extends ProvisioningCoordinator {

  def apply(actions: Vector[ProvisioningAction]): IO[ProvisioningCoordinator] = {
    actions.traverse(_.run).as(ProvisioningCoordinator)
  }

}
