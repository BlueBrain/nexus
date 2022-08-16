package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch

import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.ElasticSearchClientSpec
import ch.epfl.bluebrain.nexus.testkit.elasticsearch.ElasticSearchDocker
import org.scalatest.{Suite, Suites}

class ElasticSearchSpec extends Suites() with ElasticSearchDocker {
  override val nestedSuites: IndexedSeq[Suite] = Vector(
    new ElasticSearchClientSpec(this),
    new ElasticSearchViewsQuerySpec(this)
  )
}
