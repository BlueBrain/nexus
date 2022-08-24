package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph

import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.config.BlazegraphViewsConfig
import ch.epfl.bluebrain.nexus.delta.sdk.ConfigFixtures
import ch.epfl.bluebrain.nexus.testkit.{IOFixedClock, IOValues}

import scala.concurrent.duration._

trait BlazegraphViewsSetup extends IOValues with ConfigFixtures with IOFixedClock with Fixtures {

  val config = BlazegraphViewsConfig(
    "http://localhost",
    None,
    httpClientConfig,
    httpClientConfig,
    1.second,
    eventLogConfig,
    pagination,
    "prefix",
    10,
    1.minute
  )
}

object BlazegraphViewsSetup extends BlazegraphViewsSetup
