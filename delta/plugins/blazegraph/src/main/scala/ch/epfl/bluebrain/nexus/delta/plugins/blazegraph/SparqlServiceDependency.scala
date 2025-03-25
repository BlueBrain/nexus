package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.dependency.ComponentDescription.ServiceDescription
import ch.epfl.bluebrain.nexus.delta.kernel.dependency.ServiceDependency
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.SparqlClient

/**
  * Describes the SPARQL [[ServiceDependency]] providing a way to extract the [[ServiceDescription]]
  */
class SparqlServiceDependency(client: SparqlClient) extends ServiceDependency {

  override def serviceDescription: IO[ServiceDescription] = client.serviceDescription
}
