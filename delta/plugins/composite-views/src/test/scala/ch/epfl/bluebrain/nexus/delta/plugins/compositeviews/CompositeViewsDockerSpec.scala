package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews

import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.BlazegraphDocker
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.indexing.CompositeIndexingSpec
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.ElasticSearchDocker
import com.whisk.docker.scalatest.DockerTestKit
import org.scalatest.Suites

class CompositeViewsDockerSpec
    extends Suites(
      new BlazegraphQuerySpec,
      new ElasticSearchQuerySpec,
      new CompositeIndexingSpec
    )
    with BlazegraphDocker
    with ElasticSearchDocker
    with DockerTestKit
