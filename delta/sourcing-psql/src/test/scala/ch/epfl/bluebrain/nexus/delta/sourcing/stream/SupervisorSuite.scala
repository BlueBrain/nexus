package ch.epfl.bluebrain.nexus.delta.sourcing.stream

import cats.effect.IO
import cats.syntax.all.*
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EntityType, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Anonymous, Subject}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.postgres.Doobie
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.SuccessElem
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.ExecutionStatus.{Completed, Ignored, Running, Stopped}
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.ExecutionStrategy.{EveryNode, PersistentSingleNode, TransientSingleNode}
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.ProjectionProgress.NoProgress
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.SupervisorSetup.unapply
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.SupervisorSuite.UnstableDestroy
import ch.epfl.bluebrain.nexus.testkit.mu.ce.PatienceConfig
import fs2.Stream
import munit.{AnyFixture, Location}

import java.time.Instant
import scala.concurrent.duration.*
import cats.effect.Ref
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite

class SupervisorSuite extends NexusSuite with SupervisorSetup.Fixture with Doobie.Assertions {

  implicit private val patienceConfig: PatienceConfig = PatienceConfig(1.second, 50.millis)
  implicit private val subject: Subject               = Anonymous

  override def munitFixtures: Seq[AnyFixture[?]] = List(supervisor3_1)

  private lazy val (sv, projections, _) = unapply(supervisor3_1())
  // name1 should run on the node with index 1 in a 3-node cluster
  private val runnableByNode1           = ProjectionMetadata("test", "name1", None, None)
  // name2 should NOT run on the node with index 1 of a 3-node cluster
  private val ignoredByNode1            = ProjectionMetadata("test", "name2", None, None)
  private val random                    = ProjectionMetadata("test", "name3", None, None)

  private val project = ProjectRef.unsafe("org", "proj")

  private val rev = 1

  private def evalStream(start: IO[Unit]) =
    (_: Offset) =>
      Stream.eval(start) >> Stream
        .range(1, 21)
        .map { value =>
          SuccessElem(EntityType("entity"), nxv + "id", project, Instant.EPOCH, Offset.at(value.toLong), (), rev)
        }

  private val expectedProgress = ProjectionProgress(Offset.at(20L), Instant.EPOCH, 20, 0, 0)

  private def startProjection(metadata: ProjectionMetadata, strategy: ExecutionStrategy)(implicit loc: Location) =
    for {
      started <- Ref.of[IO, Boolean](false)
      compiled = CompiledProjection.fromStream(metadata, strategy, evalStream(started.set(true)))
      _       <- sv.run(compiled)
      _       <- started.get.assertEquals(true).eventually
    } yield ()

  private def assertCrash(metadata: ProjectionMetadata, strategy: ExecutionStrategy)(implicit loc: Location) = {
    val expectedException = new IllegalStateException("The stream crashed unexpectedly.")
    for {
      started       <- Ref.of[IO, Boolean](false)
      alreadyFailed <- Ref.of[IO, Boolean](false)
      failingOnce    = Stream.eval(alreadyFailed.get).flatMap {
                         case true  => Stream.never[IO]
                         case false => Stream.eval(alreadyFailed.set(true)) >> Stream.raiseError[IO](expectedException)
                       }
      projection     =
        CompiledProjection.fromStream(metadata, strategy, evalStream(started.set(true)).map(_ >> failingOnce))
      _             <- sv.run(projection)
      _             <- started.get.assertEquals(true).eventually
    } yield ()
  }

  private def assertDestroy(metadata: ProjectionMetadata, onDestroy: IO[Unit])(implicit loc: Location) =
    for {
      _ <- sv.destroy(metadata.name, onDestroy).assertEquals(Some(Stopped))
      _ <- sv.describe(metadata.name).assertEquals(None).eventually
      _ <- projections.progress(metadata.name).assertEquals(None)
    } yield ()

  private def assertDescribe(
      metadata: ProjectionMetadata,
      executionStrategy: ExecutionStrategy,
      restarts: Int,
      status: ExecutionStatus,
      progress: ProjectionProgress
  )(implicit loc: Location) =
    sv.describe(metadata.name)
      .assertEquals(
        Some(SupervisedDescription(metadata, executionStrategy, restarts, status, progress))
      )
      .eventually

  private def assertWatchRestarts(offset: Offset, processed: Long, discarded: Long)(implicit loc: Location) = {
    val progress = ProjectionProgress(offset, Instant.EPOCH, processed, discarded, 0)
    assertDescribe(WatchRestarts.projectionMetadata, EveryNode, 0, Running, progress)
  }

  test("Watching restart projection restarts should be running") {
    assertWatchRestarts(Offset.Start, 0, 0)
  }

  test("Ignore a projection when it is meant to run on another node") {
    for {
      flag      <- Ref.of[IO, Boolean](false)
      projection =
        CompiledProjection.fromStream(
          ignoredByNode1,
          TransientSingleNode,
          evalStream(flag.set(true))
        )
      _         <- sv.run(projection).assertEquals(Ignored)
      _         <- IO.sleep(100.millis)
      // The projection should still be ignored and should not have made any progress
      _         <- assertDescribe(ignoredByNode1, TransientSingleNode, 0, Ignored, NoProgress)
      // No progress has been saved in database either
      _         <- projections.progress(ignoredByNode1.name).assertEquals(None)
      // This means the stream has never been started
      _         <- flag.get.assertEquals(false)
    } yield ()
  }

  test("Do nothing when attempting to restart a projection when it is meant to run on another node") {
    for {
      _ <- projections.scheduleRestart(ignoredByNode1.name)
      _ <- assertWatchRestarts(Offset.at(1L), 1, 1)
      _ <- assertDescribe(ignoredByNode1, TransientSingleNode, 0, Ignored, NoProgress)
      // The restart has not been acknowledged and can be read by another node
      _ <- projections.restarts(Offset.start).assertSize(1)
    } yield ()
  }

  test("Cannot fetch ignored projection descriptions (by default)") {
    val watchProgress = ProjectionProgress(Offset.at(1L), Instant.EPOCH, 1, 1, 0)
    sv.getRunningProjections()
      .assertEquals(
        List(
          SupervisedDescription(
            metadata = WatchRestarts.projectionMetadata,
            EveryNode,
            0,
            Running,
            watchProgress
          )
        )
      )
  }

  test("Destroy an ignored projection") {
    sv.destroy(ignoredByNode1.name).assertEquals(Some(Ignored))
  }

  test("Do nothing when attempting to restart a projection when it is unknown") {
    for {
      _ <- projections.scheduleRestart("xxx")
      _ <- assertWatchRestarts(Offset.at(2L), 2, 2)
      _ <- sv.describe(ignoredByNode1.name).assertEquals(None)
      // The restart has not been acknowledged and can be read by another node
      _ <- projections.restarts(Offset.at(1L)).assertSize(1)
    } yield ()
  }

  test("Destroy an unknown projection") {
    sv.destroy("""xxx""").assertEquals(None)
  }

  test("Run a projection when it is meant to run on every node") {
    for {
      _ <- startProjection(random, EveryNode)
      // The projection should have been running successfully and made progress
      _ <- assertDescribe(random, EveryNode, 0, Completed, expectedProgress)
      // As it runs on every node, it is implicitly transient so no progress has been saved to database
      _ <- projections.progress(random.name).assertEquals(None)
    } yield ()
  }

  test("Destroy a projection running on every node") {
    assertDestroy(random, IO.unit)
  }

  test("Run a transient projection when it is meant to run on this node") {
    for {
      _ <- startProjection(runnableByNode1, TransientSingleNode)
      // The projection should have been running successfully and made progress
      _ <- assertDescribe(runnableByNode1, TransientSingleNode, 0, Completed, expectedProgress)
      // As it is transient, no progress has been saved to database
      _ <- projections.progress(runnableByNode1.name).assertEquals(None)
    } yield ()
  }

  test("Destroy a registered transient projection") {
    assertDestroy(runnableByNode1, IO.unit)
  }

  test("Run a persistent projection when it is meant to run on this node") {
    for {
      _ <- startProjection(runnableByNode1, PersistentSingleNode)
      // The projection should have been running successfully and made progress
      _ <- assertDescribe(runnableByNode1, PersistentSingleNode, 0, Completed, expectedProgress)
      // As it is persistent, progress has also been saved to database
      _ <- projections.progress(runnableByNode1.name).assertEquals(Some(expectedProgress))
    } yield ()
  }

  test("Restart a given projection when it is meant to run on this node") {
    val expectedProgress = ProjectionProgress(Offset.at(20L), Instant.EPOCH, 20, 0, 0)
    for {
      _ <- projections.scheduleRestart(runnableByNode1.name)
      _ <- assertWatchRestarts(Offset.at(3L), 3, 2)
      _ <- assertDescribe(
             runnableByNode1,
             PersistentSingleNode,
             // The number of restarts has been incremented
             restarts = 1,
             Completed,
             expectedProgress
           )
      // As it is persistent, progress has also been saved to database
      _ <- projections.progress(runnableByNode1.name).assertEquals(Some(expectedProgress))
      // The restart has been acknowledged and now longer comes up
      _ <- projections.restarts(Offset.at(2L)).assertEmpty
    } yield ()
  }

  test("Destroy a registered persistent projection") {
    assertDestroy(runnableByNode1, IO.unit)
  }

  test("Should restart a failing projection") {
    for {
      _ <- assertCrash(runnableByNode1, TransientSingleNode)
      _ <- sv.describe(runnableByNode1.name).map(_.map(_.restarts)).assertEquals(Some(1)).eventually
    } yield ()
  }

  test("Destroy a failing projection") {
    assertDestroy(runnableByNode1, IO.unit)
  }

  test("Obtain the correct running projections") {
    val watchRestartProgress = ProjectionProgress(Offset.at(3L), Instant.EPOCH, 3, 2, 0)
    val runnableProgress     = ProjectionProgress(Offset.at(20L), Instant.EPOCH, 20, 0, 0)
    for {
      _ <- startProjection(runnableByNode1, PersistentSingleNode)
      _ <- sv.getRunningProjections()
             .assertEquals(
               List(
                 SupervisedDescription(
                   WatchRestarts.projectionMetadata,
                   EveryNode,
                   restarts = 0,
                   Running,
                   progress = watchRestartProgress
                 ),
                 SupervisedDescription(
                   runnableByNode1,
                   PersistentSingleNode,
                   0,
                   Completed,
                   runnableProgress
                 )
               )
             )
             .eventually
    } yield ()
  }

  test("Run and properly destroy a projection with an unstable destroy method") {
    val projection = ProjectionMetadata("test", "unstable-global-projection", None, None)
    for {
      _               <- startProjection(projection, EveryNode)
      // Destroy the projection with a destroy method that fails and eventually succeeds
      unstableDestroy <- UnstableDestroy()
      _               <- assertDestroy(projection, unstableDestroy.attempt)
      _               <- unstableDestroy.isCompleted.assertEquals(true, "The destroy method should have completed")
    } yield ()
  }

  test("Run and properly destroy a projection with an failing destroy method") {
    val projection = ProjectionMetadata("test", "unstable-global-projection", None, None)
    val alwaysFail = IO.raiseError(new IllegalStateException("Fail !"))
    for {
      _ <- startProjection(projection, EveryNode)
      _ <- assertDestroy(projection, alwaysFail)
    } yield ()
  }
}

object SupervisorSuite {

  /**
    * Creates a destroy method which eventually succeeds after a couple of failures
    */
  final class UnstableDestroy(count: Ref[IO, Int], completed: Ref[IO, Boolean]) {
    def attempt: IO[Unit] = count
      .updateAndGet(_ + 1)
      .flatMap { i => IO.raiseWhen(i < 2)(new IllegalStateException(s"'$i' is lower than 2.")) }
      .flatTap { _ => completed.set(true) }

    def isCompleted: IO[Boolean] = completed.get
  }

  object UnstableDestroy {
    def apply(): IO[UnstableDestroy] =
      for {
        count     <- Ref.of[IO, Int](0)
        completed <- Ref.of[IO, Boolean](false)
      } yield new UnstableDestroy(count, completed)
  }

}
