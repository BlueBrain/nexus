package ch.epfl.bluebrain.nexus.delta.plugins.graph.analytics

import ch.epfl.bluebrain.nexus.delta.plugins.graph.analytics.indexing.GraphAnalyticsIndexingStreamSpec
import ch.epfl.bluebrain.nexus.testkit.ElasticSearchDocker
import com.whisk.docker.scalatest.DockerTestKit
import org.scalatest.Suites

class ElasticSearchSpec
    extends Suites(
      new GraphAnalyticsIndexingStreamSpec,
      new GraphAnalyticsSpec
    )
    with ElasticSearchDocker
    with DockerTestKit
