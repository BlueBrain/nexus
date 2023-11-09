package ch.epfl.bluebrain.nexus.delta.plugins.storage.statistics

import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageStatEntry
import ch.epfl.bluebrain.nexus.testkit.TestHelpers.jsonContentOf
import munit.FunSuite

class StorageStatisticsSerializationSuite extends FunSuite {

  test("Statistics responses are deserialized correctly") {
    val json     = jsonContentOf("storages/statistics/single-storage-stats-response.json")
    val expected = StorageStatEntry(1, 1199813)

    for {
      stats <- json.as[StorageStatEntry]
      _      = assertEquals(stats, expected)
    } yield ()
  }

}
