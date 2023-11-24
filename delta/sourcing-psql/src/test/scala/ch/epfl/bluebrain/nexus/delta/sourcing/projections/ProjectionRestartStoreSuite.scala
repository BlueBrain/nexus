package ch.epfl.bluebrain.nexus.delta.sourcing.projections

import ch.epfl.bluebrain.nexus.delta.sourcing.config.QueryConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Anonymous
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.postgres.Doobie
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.model.ProjectionRestart
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.model.ProjectionRestart.{entityType, restartId}
import ch.epfl.bluebrain.nexus.delta.sourcing.query.RefreshStrategy
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.SuccessElem
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite
import munit.AnyFixture

import java.time.Instant

class ProjectionRestartStoreSuite extends NexusSuite with Doobie.Fixture with Doobie.Assertions {

  override def munitFixtures: Seq[AnyFixture[_]] = List(doobie)

  private lazy val xas = doobie()

  private lazy val store = new ProjectionRestartStore(xas, QueryConfig(10, RefreshStrategy.Stop))

  private val pr1 = ProjectionRestart("proj1", Instant.EPOCH, Anonymous)
  private val pr2 = ProjectionRestart("proj2", Instant.EPOCH.plusSeconds(5L), Anonymous)

  private def toElem(id: Offset, restart: ProjectionRestart) =
    SuccessElem(entityType, restartId(id), None, restart.instant, id, restart, 1)

  test("Save a projection restart") {
    store.save(pr1).assertEquals(())
  }

  test("Save a second projection restart") {
    store.save(pr2).assertEquals(())
  }

  test("Stream projection restarts") {
    store
      .stream(Offset.start)
      .assert(toElem(Offset.at(1L), pr1), toElem(Offset.at(2L), pr2))
  }

  test("Delete older restarts and stream again") {
    for {
      _ <- store.deleteExpired(Instant.EPOCH.plusSeconds(2L))
      _ <- store.stream(Offset.start).assert(toElem(Offset.at(2L), pr2))
    } yield ()
  }

  test("Acknowledge restart 2 and stream again") {
    for {
      _ <- store.acknowledge(Offset.at(2L))
      _ <- store.stream(Offset.start).assert()
    } yield ()
  }

}
