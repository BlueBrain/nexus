package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages

import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageStatsCollection.StorageStatEntry
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.iriStringContextSyntax
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.testkit.TestHelpers
import ch.epfl.bluebrain.nexus.testkit.bio.BioSuite
import monix.bio.IO

class StatisticsSuite extends BioSuite with TestHelpers {

  private val project = ProjectRef.unsafe("org", "proj")

  test("Statistics responses with multiple storages are handled correctly") {
    val response   = jsonContentOf("storages/statistics/multiple-storages-stats-response.json")
    val statistics = StoragesStatistics.apply(
      _ => IO.pure(response),
      (_, _) => IO.pure(Iri.unsafe("storageId"))
    )

    val expected = Map(
      iri"https://bluebrain.github.io/nexus/vocabulary/diskStorageDefault" ->
        StorageStatEntry(2, 5096786),
      iri"https://bluebrain.github.io/nexus/vocabulary/remote"             ->
        StorageStatEntry(1, 1234567)
    )

    for {
      allStats     <- statistics.get()
      _             = assertEquals(allStats, expected)
      projectStats <- statistics.get(project)
      _             = assertEquals(projectStats, expected)
    } yield ()
  }

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

  test("Query for project scoped storages stats is constructed correctly from template") {
    val expected = jsonObjectContentOf("/storages/statistics/multiple-storage-project-scoped-stats-query.json")
    for {
      query <- StoragesStatistics.statsByProjectQuery(project)
      _      = assertEquals(query, expected)
    } yield ()
  }

  test("Query for project scoped single storage stats is constructed correctly from template") {
    val storageId = iri"https://bluebrain.github.io/nexus/vocabulary/diskStorageDefault"
    val expected  = jsonObjectContentOf("/storages/statistics/single-storage-stats-query.json")
    for {
      query <- StoragesStatistics.statsByIdAndProjectQuery(storageId, project)
      _      = assertEquals(query, expected)
    } yield ()
  }

}
