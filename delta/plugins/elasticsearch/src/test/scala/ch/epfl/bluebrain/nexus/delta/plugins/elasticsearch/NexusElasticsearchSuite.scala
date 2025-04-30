package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch

import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite

import scala.concurrent.duration.{Duration, DurationInt}

abstract class NexusElasticsearchSuite extends NexusSuite {

  override def munitIOTimeout: Duration = 120.seconds

}
