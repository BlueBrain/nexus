package ch.epfl.bluebrain.nexus.delta.sdk

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.sdk.model.ComponentDescription.ServiceDescription

/**
  * A description of a service that is used by the system
  */
trait ServiceDependency {

  /**
    * @return
    *   the service description of the current dependency
    */
  def serviceDescription: IO[ServiceDescription]
}
