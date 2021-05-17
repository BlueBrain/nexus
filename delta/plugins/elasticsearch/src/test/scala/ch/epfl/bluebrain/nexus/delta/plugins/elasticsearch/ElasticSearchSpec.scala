package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch

import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.ElasticSearchClientSpec
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing.ElasticSearchIndexingSpec
import com.whisk.docker.scalatest.DockerTestKit
import org.scalatest.Suites

class ElasticSearchSpec
    extends Suites(new ElasticSearchClientSpec, new ElasticSearchIndexingSpec, new ElasticSearchViewsQuerySpec)
    with ElasticSearchDocker
    with DockerTestKit
