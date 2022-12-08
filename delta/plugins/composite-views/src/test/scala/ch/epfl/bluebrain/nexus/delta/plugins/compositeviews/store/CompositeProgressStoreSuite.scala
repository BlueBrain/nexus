package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.store

import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.stream.CompositeBranch
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.stream.CompositeBranch.Run
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.views.ViewRef
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.ProjectionProgress
import ch.epfl.bluebrain.nexus.testkit.IOFixedClock
import ch.epfl.bluebrain.nexus.testkit.bio.BioSuite
import ch.epfl.bluebrain.nexus.testkit.postgres.Doobie
import munit.AnyFixture

import java.time.Instant

class CompositeProgressStoreSuite extends BioSuite with IOFixedClock with Doobie.Fixture with Doobie.Assertions {

  override def munitFixtures: Seq[AnyFixture[_]] = List(doobie)

  private lazy val xas = doobie()

  private lazy val store = new CompositeProgressStore(xas)

  private val project = ProjectRef.unsafe("org", "proj")
  private val view    = ViewRef(project, nxv + "id")
  private val rev     = 2
  private val source  = nxv + "source"
  private val target  = nxv + "target"

  private val mainBranch    = CompositeBranch(view, rev, source, target, Run.Main)
  private val rebuildBranch = CompositeBranch(view, rev, source, target, Run.Main)

  private val mainProgress    = ProjectionProgress(Offset.At(42L), Instant.EPOCH, 5, 2, 1)
  private val rebuildProgress = ProjectionProgress(Offset.At(21L), Instant.EPOCH, 4, 1, 0)

  test("Return no progress") {
    store.progress(view, rev).assert(Map.empty)
  }

  test("Save progress for both branches") {
    for {
      _ <- store.save(mainBranch, mainProgress)
      _ <- store.save(rebuildBranch, rebuildProgress)
    } yield ()
  }

  test("Return new progress") {
    store
      .progress(view, rev)
      .assert(
        Map(
          mainBranch    -> mainProgress,
          rebuildBranch -> rebuildProgress
        )
      )
  }

  test("Update progress for main branch") {
    val newProgress = mainProgress.copy(processed = 100L)
    for {
      _ <- store.save(mainBranch, newProgress)
      _ <- store.save(rebuildBranch, rebuildProgress)
      _ <- store
             .progress(view, rev)
             .assert(
               Map(
                 mainBranch    -> newProgress,
                 rebuildBranch -> rebuildProgress
               )
             )
    } yield ()
  }

  test("Delete progresses for the view") {
    for {
      _ <- store.deleteAll(view, rev)
      _ <- store.progress(view, rev).assert(Map.empty)
    } yield ()
  }

}
