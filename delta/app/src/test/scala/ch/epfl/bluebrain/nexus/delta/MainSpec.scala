package ch.epfl.bluebrain.nexus.delta

import ch.epfl.bluebrain.nexus.testkit.elasticsearch.ElasticSearchDocker
import org.scalatest.{Suite, Suites}

class MainSpec extends Suites with ElasticSearchDocker {
  override val nestedSuites: IndexedSeq[Suite] = Vector(
    new PostgresMainSpec
  )
}
