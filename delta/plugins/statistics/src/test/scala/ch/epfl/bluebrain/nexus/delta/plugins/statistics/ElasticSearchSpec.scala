package ch.epfl.bluebrain.nexus.delta.plugins.statistics

import ch.epfl.bluebrain.nexus.delta.plugins.statistics.indexing.StatisticsIndexingStreamSpec
import ch.epfl.bluebrain.nexus.testkit.ElasticSearchDocker
import com.whisk.docker.scalatest.DockerTestKit
import org.scalatest.Suites

class ElasticSearchSpec
    extends Suites(
      new StatisticsIndexingStreamSpec,
      new StatisticsSpec
    )
    with ElasticSearchDocker
    with DockerTestKit
