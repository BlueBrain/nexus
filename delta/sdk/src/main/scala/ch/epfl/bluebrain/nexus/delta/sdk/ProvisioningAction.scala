package ch.epfl.bluebrain.nexus.delta.sdk

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.sdk.ProvisioningAction.Outcome

/**
  * Provisioning action to run at startup
  */
trait ProvisioningAction {

  def run: IO[Outcome]

}

object ProvisioningAction {

  sealed trait Outcome

  object Outcome {
    case object Success  extends Outcome
    case object Skipped  extends Outcome
    case object Disabled extends Outcome
    case object Error    extends Outcome
  }

}
