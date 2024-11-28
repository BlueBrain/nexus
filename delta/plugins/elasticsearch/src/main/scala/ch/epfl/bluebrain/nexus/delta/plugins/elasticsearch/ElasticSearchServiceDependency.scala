package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.dependency.ComponentDescription.ServiceDescription
import ch.epfl.bluebrain.nexus.delta.kernel.dependency.ServiceDependency
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.ElasticSearchClient

/**
  * Describes the Elasticsearch [[ServiceDependency]] providing a way to extract the [[ServiceDescription]] from
  * Elasticsearch
  */
class ElasticSearchServiceDependency(client: ElasticSearchClient) extends ServiceDependency {

  override def serviceDescription: IO[ServiceDescription] =
    client.serviceDescription
}
