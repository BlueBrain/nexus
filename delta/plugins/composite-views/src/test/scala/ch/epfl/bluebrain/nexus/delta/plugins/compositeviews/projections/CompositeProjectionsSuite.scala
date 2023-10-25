package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.projections

import cats.effect.concurrent.Ref
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeRestart.FullRestart
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.store.{CompositeProgressStore, CompositeRestartStore}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.stream.CompositeBranch.Run
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.stream.{CompositeBranch, CompositeProgress}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.ConfigFixtures
import ch.epfl.bluebrain.nexus.delta.sdk.views.{IndexingRev, IndexingViewRef, ViewRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.config.BatchConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Anonymous
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.postgres.Doobie
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.ProjectionProgress
import ch.epfl.bluebrain.nexus.testkit.mu.bio.{BioSuite, PatienceConfig}
import fs2.Stream
import monix.bio.Task
import munit.AnyFixture

import java.time.Instant
import scala.concurrent.duration._

class CompositeProjectionsSuite extends BioSuite with Doobie.Fixture with Doobie.Assertions with ConfigFixtures {

  override def munitFixtures: Seq[AnyFixture[_]] = List(doobie)

  private lazy val xas = doobie()

  implicit private val patienceConfig: PatienceConfig = PatienceConfig(5.seconds, 100.millis)

  private lazy val compositeRestartStore  = new CompositeRestartStore(xas)
  private lazy val compositeProgressStore = new CompositeProgressStore(xas)
  private lazy val projections            =
    CompositeProjections(compositeRestartStore, xas, queryConfig, BatchConfig(10, 50.millis), 3.seconds)

  private val project     = ProjectRef.unsafe("org", "proj")
  private val viewRef     = ViewRef(project, nxv + "id")
  private val indexingRev = IndexingRev(2)
  private val view        = IndexingViewRef(viewRef, indexingRev)

  private val source  = nxv + "source"
  private val target1 = nxv + "target1"
  private val target2 = nxv + "target2"

  private val mainBranch1   = CompositeBranch(source, target1, Run.Main)
  private val mainProgress1 = ProjectionProgress(Offset.At(42L), Instant.EPOCH, 5, 2, 1)

  private val mainBranch2   = CompositeBranch(source, target2, Run.Main)
  private val mainProgress2 = ProjectionProgress(Offset.At(22L), Instant.EPOCH, 2, 1, 0)

  private val viewRef2      = ViewRef(project, nxv + "id2")
  private val view2         = IndexingViewRef(viewRef2, indexingRev)
  private val view2Progress = ProjectionProgress(Offset.At(999L), Instant.EPOCH, 514, 140, 0)

  // Check that view 2 is not affected by changes on view 1
  private def assertView2 = {
    val expected = Map(mainBranch1 -> view2Progress)
    compositeProgressStore.progress(view2).assert(expected)
  }

  // Save progress for view 1
  private def saveView1 =
    for {
      _ <- compositeProgressStore.save(view, mainBranch1, mainProgress1)
      _ <- compositeProgressStore.save(view, mainBranch2, mainProgress2)
    } yield ()

  test("Save progress for all branches and views") {
    for {
      _ <- saveView1
      _ <- compositeProgressStore.save(view2, mainBranch1, view2Progress)
    } yield ()
  }

  test("Return new progress") {
    val expected = CompositeProgress(
      Map(mainBranch1 -> mainProgress1, mainBranch2 -> mainProgress2)
    )

    for {
      _ <- projections.progress(view).assert(expected)
      _ <- assertView2
    } yield ()
  }

  test("Save a composite restart and reset progress") {
    val restart = FullRestart(viewRef, Instant.EPOCH, Anonymous)
    for {
      value   <- Ref.of[Task, Int](0)
      inc      = Stream.eval(value.getAndUpdate(_ + 1)) ++ Stream.never[Task]
      _       <- inc.through(projections.handleRestarts(viewRef)).compile.drain.start
      _       <- value.get.eventually(1)
      _       <- compositeRestartStore.save(restart)
      expected = CompositeProgress(
                   Map(mainBranch1 -> ProjectionProgress.NoProgress, mainBranch2 -> ProjectionProgress.NoProgress)
                 )
      _       <- projections.progress(view).eventually(expected)
      _       <- compositeRestartStore.head(viewRef).assertNone
      _       <- value.get.eventually(2)
      _       <- assertView2
    } yield ()
  }

  test("Delete all progress") {
    for {
      _ <- projections.deleteAll(view)
      _ <- projections.progress(view).assert(CompositeProgress(Map.empty))
    } yield ()

  }
}
