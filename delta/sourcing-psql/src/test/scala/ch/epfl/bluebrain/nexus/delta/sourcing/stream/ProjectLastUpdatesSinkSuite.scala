package ch.epfl.bluebrain.nexus.delta.sourcing.stream

import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sourcing.config.QueryConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EntityType, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.postgres.Doobie
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.{ProjectLastUpdateStore, ProjectLastUpdateStream}
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.model.ProjectLastUpdate
import ch.epfl.bluebrain.nexus.delta.sourcing.query.RefreshStrategy
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.{DroppedElem, SuccessElem}
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.config.BatchConfig
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite
import fs2.Chunk
import munit.AnyFixture

import java.time.Instant
import java.time.temporal.ChronoUnit
import scala.concurrent.duration.DurationInt

class ProjectLastUpdatesSinkSuite extends NexusSuite with Doobie.Fixture {

  override def munitFixtures: Seq[AnyFixture[?]] = List(doobie)

  private lazy val xas    = doobie()
  private lazy val store  = ProjectLastUpdateStore(xas)
  private lazy val stream = ProjectLastUpdateStream(xas, QueryConfig(10, RefreshStrategy.Stop))

  private val now = Instant.now().truncatedTo(ChronoUnit.SECONDS)

  test("Process the incoming updates and update the values in database accordingly") {
    val project1        = ProjectRef.unsafe("org", "project1")
    val project2        = ProjectRef.unsafe("org", "project2")
    val project3        = ProjectRef.unsafe("org", "project3")
    val existingUpdates = List(
      ProjectLastUpdate(project1, now.minusSeconds(5L), Offset.at(42L)),
      ProjectLastUpdate(project2, now.minusSeconds(10L), Offset.at(35L))
    )

    val entityType = EntityType("test")

    val incoming = Chunk(
      SuccessElem(entityType, nxv + "id1", project1, now.minusSeconds(2L), Offset.at(75L), (), 1),
      DroppedElem(entityType, nxv + "id2", project3, now, Offset.at(95L), 1),
      DroppedElem(entityType, nxv + "id3", project1, now, Offset.at(100L), 1)
    )

    for {
      // Injecting initial updates
      _       <- store.save(existingUpdates)
      // Processing new incoming elems
      sink     = ProjectLastUpdatesSink(store, BatchConfig(3, 10.millis))
      // Checking new updates in the db
      _       <- sink(incoming)
      expected = List(
                   ProjectLastUpdate(project2, now.minusSeconds(10L), Offset.at(35L)),
                   ProjectLastUpdate(project3, now, Offset.at(95L)),
                   ProjectLastUpdate(project1, now, Offset.at(100L))
                 )
      _       <- stream(Offset.start).assert(expected)
    } yield ()
  }

}
