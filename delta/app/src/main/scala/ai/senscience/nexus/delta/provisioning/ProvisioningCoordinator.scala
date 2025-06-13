package ai.senscience.nexus.delta.provisioning

import cats.effect.IO
import cats.syntax.all.*
import ch.epfl.bluebrain.nexus.delta.sdk.ProvisioningAction

trait ProvisioningCoordinator

object ProvisioningCoordinator extends ProvisioningCoordinator {

  def apply(actions: Vector[ProvisioningAction]): IO[ProvisioningCoordinator] = {
    actions.traverse(_.run).as(ProvisioningCoordinator)
  }

}
