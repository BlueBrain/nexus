package ch.epfl.bluebrain.nexus.delta.sourcing.projections

import ch.epfl.bluebrain.nexus.delta.sourcing.config.QueryConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.postgres.Doobie
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.model.ProjectLastUpdate
import ch.epfl.bluebrain.nexus.delta.sourcing.query.RefreshStrategy
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite
import munit.AnyFixture

import java.time.Instant
import java.time.temporal.ChronoUnit

class ProjectLastUpdateStoreSuite extends NexusSuite with Doobie.Fixture {

  override def munitFixtures: Seq[AnyFixture[_]] = List(doobie)

  private lazy val xas    = doobie()
  private lazy val store  = ProjectLastUpdateStore(xas)
  private lazy val stream = ProjectLastUpdateStream(xas, QueryConfig(10, RefreshStrategy.Stop))

  private val now = Instant.now().truncatedTo(ChronoUnit.SECONDS)

  test("Save, fetch, update and fetch again and delete and fetch one last time") {
    val project1            = ProjectRef.unsafe("org", "proj1")
    val lastProject1        = ProjectLastUpdate(project1, Instant.EPOCH, Offset.at(42L))
    val lastProject1Updated = ProjectLastUpdate(project1, now.minusSeconds(5L), Offset.at(66L))
    val project2            = ProjectRef.unsafe("org", "proj2")
    val lastProject2        = ProjectLastUpdate(project2, now.minusSeconds(2L), Offset.at(123L))

    for {
      // Init
      _                    <- store.save(List(lastProject1, lastProject2))
      expectedAll           = List(lastProject1, lastProject2)
      _                    <- stream(Offset.start).assert(expectedAll)
      offset42              = Offset.at(42L)
      expectedAfter42       = List(lastProject2)
      _                    <- stream(offset42).assert(expectedAfter42)
      // Update
      _                    <- store.save(List(lastProject1Updated))
      expectedAfterUpdate   = List(lastProject1Updated, lastProject2)
      _                    <- stream(offset42).assert(expectedAfterUpdate)
      // Deletion
      _                    <- store.delete(project1)
      expectedAfterDeletion = List(lastProject2)
      _                    <- stream(offset42).assert(expectedAfterDeletion)
    } yield ()
  }

}
