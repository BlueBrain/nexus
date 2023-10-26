package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.ElasticSearchClient
import ch.epfl.bluebrain.nexus.delta.sdk.ServiceDependency
import ch.epfl.bluebrain.nexus.delta.sdk.model.ComponentDescription.ServiceDescription
import ch.epfl.bluebrain.nexus.delta.kernel.effect.migration._

/**
  * Describes the remote storage [[ServiceDependency]] providing a way to extract the [[ServiceDescription]] from a
  * remote storage calling its ''/version'' endpoint
  */
class ElasticSearchServiceDependency(client: ElasticSearchClient) extends ServiceDependency {

  override def serviceDescription: IO[ServiceDescription] =
    client.serviceDescription
}
