package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.store

import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeRestart
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeRestart.entityType
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.views.ViewRef
import ch.epfl.bluebrain.nexus.delta.sourcing.config.QueryConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Anonymous
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.query.RefreshStrategy
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.SuccessElem
import ch.epfl.bluebrain.nexus.testkit.IOFixedClock
import ch.epfl.bluebrain.nexus.testkit.bio.BioSuite
import ch.epfl.bluebrain.nexus.testkit.postgres.Doobie
import munit.AnyFixture

import java.time.Instant

class CompositeRestartStoreSuite extends BioSuite with IOFixedClock with Doobie.Fixture with Doobie.Assertions {

  override def munitFixtures: Seq[AnyFixture[_]] = List(doobie)

  private lazy val xas = doobie()

  private lazy val store = new CompositeRestartStore(xas, QueryConfig(10, RefreshStrategy.Stop))

  private val proj = ProjectRef.unsafe("org", "proj")

  private val id1     = nxv + "id1"
  private val viewRef = ViewRef(proj, id1)

  private val cr1 = CompositeRestart.Full(proj, id1, Instant.EPOCH, Anonymous)

  private val id2 = nxv + "id2"
  private val cr2 = CompositeRestart.Full(proj, id2, Instant.EPOCH, Anonymous)

  private val projection = nxv + "projection"
  private val cr3        = CompositeRestart.PartialRestart(proj, id1, projection, Instant.EPOCH.plusSeconds(5L), Anonymous)

  private def toElem(offset: Offset, restart: CompositeRestart) =
    SuccessElem(entityType, restart.id, Some(restart.project), restart.instant, offset, restart, 1)

  test("Save composite restarts") {
    for {
      _ <- store.save(cr1).assert(())
      _ <- store.save(cr2).assert(())
      _ <- store.save(cr3).assert(())
    } yield ()
  }

  test("Stream composite restarts") {
    store
      .stream(viewRef, Offset.start)
      .assert(toElem(Offset.at(1L), cr1), toElem(Offset.at(3L), cr3))
  }

  test("Delete older restarts and stream again") {
    for {
      _ <- store.deleteExpired(Instant.EPOCH.plusSeconds(2L))
      _ <- store.stream(viewRef, Offset.start).assert(toElem(Offset.at(3L), cr3))
    } yield ()
  }

  test("Acknowledge restart 3 and stream again") {
    for {
      _ <- store.acknowledge(Offset.at(3L))
      _ <- store.stream(viewRef, Offset.start).assert()
    } yield ()
  }

}
