package ch.epfl.bluebrain.nexus.delta.plugins.storage.statistics

import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StoragesStatistics
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageStatEntry
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.iriStringContextSyntax
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.testkit.TestHelpers.jsonContentOf
import ch.epfl.bluebrain.nexus.testkit.bio.BioSuite
import monix.bio.IO

class StorageStatisticsSerializationSuite extends BioSuite {

  val project: ProjectRef = ProjectRef.unsafe("org", "proj")

  test("Statistics responses with a single storage are handled correctly") {
    val response   = jsonContentOf("storages/statistics/single-storage-stats-response.json")
    val statistics = StoragesStatistics.apply(
      _ => IO.pure(response),
      (_, _) => IO.pure(Iri.unsafe("storageId"))
    )

    val expected  = StorageStatEntry(1, 1199813)
    val storageId = iri"storageId"

    for {
      storageStatEntry <- statistics.get(storageId, project)
      _                 = assertEquals(storageStatEntry, expected)
    } yield ()
  }

}
