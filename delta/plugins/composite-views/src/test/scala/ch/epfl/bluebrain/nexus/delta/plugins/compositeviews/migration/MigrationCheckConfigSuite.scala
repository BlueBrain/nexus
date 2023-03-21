package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.migration

import munit.FunSuite
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._

import concurrent.duration._

class MigrationCheckConfigSuite extends FunSuite {

  test("Load with default values") {
    assertEquals(
      MigrationCheckConfig.load(),
      MigrationCheckConfig(
        "delta",
        uri"http://blazegraph:9999/blazegraph",
        uri"http://delta:8080/v1",
        uri"http://delta-new:8080/v1",
        20.seconds,
        2
      )
    )
  }

}
