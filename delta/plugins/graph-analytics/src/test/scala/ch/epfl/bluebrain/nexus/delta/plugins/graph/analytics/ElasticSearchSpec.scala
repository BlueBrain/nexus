package ch.epfl.bluebrain.nexus.delta.plugins.graph.analytics

import ch.epfl.bluebrain.nexus.testkit.elasticsearch.ElasticSearchDocker
import org.scalatest.{Suite, Suites}

class ElasticSearchSpec extends Suites with ElasticSearchDocker {
  override val nestedSuites: IndexedSeq[Suite] = Vector(
    new GraphAnalyticsSpec(this)
  )
}
