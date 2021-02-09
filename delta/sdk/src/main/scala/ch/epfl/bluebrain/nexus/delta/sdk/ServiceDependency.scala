package ch.epfl.bluebrain.nexus.delta.sdk

import ch.epfl.bluebrain.nexus.delta.sdk.model.ComponentDescription.ServiceDescription
import monix.bio.UIO

/**
  * A description of a service that is used by the system
  */
trait ServiceDependency {

  /**
    * @return the service description of the current dependency
    */
  def serviceDescription: UIO[ServiceDescription]
}
