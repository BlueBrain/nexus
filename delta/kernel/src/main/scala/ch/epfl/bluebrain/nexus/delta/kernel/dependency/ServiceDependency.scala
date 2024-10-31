package ch.epfl.bluebrain.nexus.delta.kernel.dependency

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.dependency.ComponentDescription.ServiceDescription

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
