package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.dependency.ComponentDescription.ServiceDescription
import ch.epfl.bluebrain.nexus.delta.kernel.dependency.ServiceDependency
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.BlazegraphClient

/**
  * Describes the Blazegraph [[ServiceDependency]] providing a way to extract the [[ServiceDescription]] from
 * its ''/status'' endpoint
  */
class BlazegraphServiceDependency(client: BlazegraphClient) extends ServiceDependency {

  override def serviceDescription: IO[ServiceDescription] = client.serviceDescription
}
