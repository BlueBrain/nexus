package ch.epfl.bluebrain.nexus.delta.sourcing.stream

import cats.effect.concurrent.Ref
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sourcing.model.EntityType
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Anonymous, Subject}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.SuccessElem
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.ExecutionStrategy.PersistentSingleNode
import ch.epfl.bluebrain.nexus.testkit.bio.{BioSuite, PatienceConfig}
import ch.epfl.bluebrain.nexus.testkit.postgres.Doobie
import fs2.Stream
import monix.bio.Task
import munit.AnyFixture

import java.time.Instant
import scala.concurrent.duration._

class SupervisionSuite extends BioSuite with SupervisorSetup.Fixture with Doobie.Assertions {

  implicit private val patienceConfig: PatienceConfig = PatienceConfig(1.second, 50.millis)
  implicit private val subject: Subject               = Anonymous

  override def munitFixtures: Seq[AnyFixture[_]] = List(supervisor3_1)

  private lazy val (sv, projections) = supervisor3_1()
  // name1 should run on the node with index 1 in a 3-node cluster
  private val projection1            = ProjectionMetadata("test", "name1", None, None)
  // name2 should NOT run on the node with index 1 of a 3-node cluster
  private val projection2            = ProjectionMetadata("test", "name2", None, None)
  private val projection2Description = SupervisedDescription(
    projection2,
    ExecutionStrategy.TransientSingleNode,
    0,
    ExecutionStatus.Ignored,
    ProjectionProgress.NoProgress
  )
  private val projection3            = ProjectionMetadata("test", "name3", None, None)

  private val rev = 1

  private def evalStream(start: Task[Unit]) =
    (_: Offset) =>
      Stream.eval(start) >> Stream
        .range(1, 21)
        .map { value =>
          SuccessElem(EntityType("entity"), nxv + "id", None, Instant.EPOCH, Offset.at(value.toLong), (), rev)
        }

  private val expectedProgress = ProjectionProgress(Offset.at(20L), Instant.EPOCH, 20, 0, 0)

  test("Watching restart projection restarts should be running") {
    sv.describe(Supervisor.watchRestartMetadata.name)
      .assertSome(
        SupervisedDescription(
          Supervisor.watchRestartMetadata,
          ExecutionStrategy.EveryNode,
          0,
          ExecutionStatus.Running,
          ProjectionProgress.NoProgress
        )
      )
  }

  test("Ignore a projection when it is meant to run on another node") {
    for {
      flag      <- Ref.of[Task, Boolean](false)
      projection =
        CompiledProjection.fromStream(projection2, ExecutionStrategy.TransientSingleNode, evalStream(flag.set(true)))
      _         <- sv.run(projection).assert(ExecutionStatus.Ignored)
      _         <- Task.sleep(100.millis)
      // The projection should still be ignored and should not have made any progress
      _         <- sv.describe(projection2.name).assertSome(projection2Description)
      // No progress has been saved in database either
      _         <- projections.progress(projection2.name).assertNone
      // This means the stream has never been started
      _         <- flag.get.assert(false)
    } yield ()
  }

  test("Do nothing when attempting to restart a projection when it is meant to run on another node") {
    for {
      _ <- projections.scheduleRestart(projection2.name)
      _ <- sv.describe(Supervisor.watchRestartMetadata.name)
             .eventuallySome(
               SupervisedDescription(
                 Supervisor.watchRestartMetadata,
                 ExecutionStrategy.EveryNode,
                 0,
                 ExecutionStatus.Running,
                 // Watching restart projection has captured the restart and ignored
                 ProjectionProgress(Offset.at(1L), Instant.EPOCH, 1, 1, 0)
               )
             )
      _ <- sv.describe(projection2.name)
             .eventuallySome(
               SupervisedDescription(
                 metadata = projection2,
                 executionStrategy = ExecutionStrategy.TransientSingleNode,
                 restarts = 0,
                 status = ExecutionStatus.Ignored,
                 progress = ProjectionProgress.NoProgress
               )
             )
      // The restart has not been acknowledged and can be read by another node
      _ <- projections.restarts(Offset.start).assertSize(1)
    } yield ()
  }

  test("Cannot fetch ignored projection descriptions (by default)") {
    sv.getRunningProjections()
      .assert(
        List(
          SupervisedDescription(
            metadata = ProjectionMetadata(
              module = "system",
              name = "watch-restarts",
              project = None,
              resourceId = None
            ),
            ExecutionStrategy.EveryNode,
            restarts = 0,
            ExecutionStatus.Running,
            progress = ProjectionProgress(
              offset = Offset.at(1L),
              instant = Instant.EPOCH,
              processed = 1,
              discarded = 1,
              failed = 0
            )
          )
        )
      )
  }

  test("Destroy an ignored projection") {
    sv.destroy(projection2.name).assertSome(ExecutionStatus.Ignored)
  }

  test("Do nothing when attempting to restart a projection when it is unknown") {
    for {
      _ <- projections.scheduleRestart("xxx")
      _ <- sv.describe(Supervisor.watchRestartMetadata.name)
             .eventuallySome(
               SupervisedDescription(
                 Supervisor.watchRestartMetadata,
                 ExecutionStrategy.EveryNode,
                 0,
                 ExecutionStatus.Running,
                 // Watching restart projection has captured the restart and ignored
                 ProjectionProgress(Offset.at(2L), Instant.EPOCH, 2, 2, 0)
               )
             )
      _ <- sv.describe(projection2.name).assertNone
      // The restart has not been acknowledged and can be read by another node
      _ <- projections.restarts(Offset.at(1L)).assertSize(1)
    } yield ()
  }

  test("Destroy an unknown projection") {
    sv.destroy("""xxx""").assertNone
  }

  test("Run a projection when it is meant to run on every node") {
    for {
      flag      <- Ref.of[Task, Boolean](false)
      projection =
        CompiledProjection.fromStream(projection3, ExecutionStrategy.EveryNode, evalStream(flag.set(true)))
      _         <- sv.run(projection)
      // This means the stream has been started
      _         <- flag.get.eventually(true)
      // The projection should have been running successfully and made progress
      _         <- sv.describe(projection3.name)
                     .eventuallySome(
                       SupervisedDescription(
                         projection3,
                         ExecutionStrategy.EveryNode,
                         0,
                         ExecutionStatus.Completed,
                         expectedProgress
                       )
                     )
      // As it runs on every node, it is implicitly transient so no progress has been saved to database
      _         <- projections.progress(projection3.name).assertNone
    } yield ()
  }

  test("Destroy a projection running on every node") {
    sv.destroy(projection3.name).assertSome(ExecutionStatus.Stopped)
  }

  test("Run a transient projection when it is meant to run on this node") {
    for {
      flag      <- Ref.of[Task, Boolean](false)
      projection =
        CompiledProjection.fromStream(projection1, ExecutionStrategy.TransientSingleNode, evalStream(flag.set(true)))
      _         <- sv.run(projection)
      // This means the stream has been started
      _         <- flag.get.eventually(true)
      // The projection should have been running successfully and made progress
      _         <- sv.describe(projection1.name)
                     .eventuallySome(
                       SupervisedDescription(
                         projection1,
                         ExecutionStrategy.TransientSingleNode,
                         0,
                         ExecutionStatus.Completed,
                         expectedProgress
                       )
                     )
      // As it is transient, no progress has been saved to database
      _         <- projections.progress(projection1.name).assertNone
    } yield ()
  }

  test("Destroy a registered transient projection") {
    for {
      _ <- sv.destroy(projection1.name).assertSome(ExecutionStatus.Stopped)
      _ <- sv.describe(projection1.name).assertNone
    } yield ()
  }

  test("Run a persistent projection when it is meant to run on this node") {
    for {
      flag      <- Ref.of[Task, Boolean](false)
      projection =
        CompiledProjection.fromStream(projection1, PersistentSingleNode, evalStream(flag.set(true)))
      _         <- sv.run(projection)
      // This means the stream has been started
      _         <- flag.get.eventually(true)
      // The projection should have been running successfully and made progress
      _         <- sv.describe(projection1.name)
                     .eventuallySome(
                       SupervisedDescription(
                         projection1,
                         PersistentSingleNode,
                         0,
                         ExecutionStatus.Completed,
                         expectedProgress
                       )
                     )
      // As it is persistent, progress has also been saved to database
      _         <- projections.progress(projection1.name).assertSome(expectedProgress)
    } yield ()
  }

  test("Restart a given projection when it is meant to run on this node") {
    val expectedProgress = ProjectionProgress(Offset.at(20L), Instant.EPOCH, 20, 0, 0)
    for {
      _ <- projections.scheduleRestart(projection1.name)
      _ <- sv.describe(Supervisor.watchRestartMetadata.name)
             .eventuallySome(
               SupervisedDescription(
                 Supervisor.watchRestartMetadata,
                 ExecutionStrategy.EveryNode,
                 0,
                 ExecutionStatus.Running,
                 // Watching restart projection has captured the restart and taken into account
                 ProjectionProgress(Offset.at(3L), Instant.EPOCH, 3, 2, 0)
               )
             )
      _ <- sv.describe(projection1.name)
             .eventuallySome(
               SupervisedDescription(
                 projection1,
                 PersistentSingleNode,
                 // The number of restarts has been incremented
                 restarts = 1,
                 ExecutionStatus.Completed,
                 expectedProgress
               )
             )
      // As it is persistent, progress has also been saved to database
      _ <- projections.progress(projection1.name).assertSome(expectedProgress)
      // The restart has been acknowledged and now longer comes up
      _ <- projections.restarts(Offset.at(2L)).assertEmpty
    } yield ()
  }

  test("Destroy a registered persistent projection") {
    for {
      _ <- sv.destroy(projection1.name).assertSome(ExecutionStatus.Stopped)
      _ <- sv.describe(projection1.name).assertNone
      _ <- projections.progress(projection1.name).assertNone
    } yield ()
  }

  test("Should restart a failing projection") {
    val expectedException = new IllegalStateException("The stream crashed unexpectedly.")
    for {
      flag      <- Ref.of[Task, Boolean](false)
      _         <- projections.progress(projection1.name).assertNone
      projection =
        CompiledProjection.fromStream(
          projection1,
          ExecutionStrategy.TransientSingleNode,
          evalStream(flag.set(true)).map(_ >> Stream.raiseError[Task](expectedException))
        )
      _         <- sv.run(projection).eventually(ExecutionStatus.Running)
      _         <- flag.get.eventually(true)
      _         <- sv.describe(projection1.name).map(_.exists(_.restarts > 0)).eventually(true)
    } yield ()
  }

  test("Destroy a failing projection") {
    for {
      _ <- sv.destroy(projection1.name).assertSome(ExecutionStatus.Stopped)
      _ <- sv.describe(projection1.name).eventuallyNone
      _ <- projections.progress(projection1.name).assertNone
    } yield ()
  }

  test("Obtain the correct running projections") {
    val expectedProgress = ProjectionProgress(
      Offset.at(20L),
      Instant.EPOCH,
      20,
      0,
      0
    )
    for {
      flag      <- Ref.of[Task, Boolean](false)
      projection =
        CompiledProjection.fromStream(projection1, PersistentSingleNode, evalStream(flag.set(true)))
      _         <- sv.run(projection).eventually(ExecutionStatus.Running)
      _         <- flag.get.eventually(true)
      _         <- sv.getRunningProjections()
                     .eventually(
                       List(
                         SupervisedDescription(
                           metadata = Supervisor.watchRestartMetadata,
                           ExecutionStrategy.EveryNode,
                           restarts = 0,
                           ExecutionStatus.Running,
                           progress = ProjectionProgress(
                             offset = Offset.at(3L),
                             instant = Instant.EPOCH,
                             processed = 3,
                             discarded = 2,
                             failed = 0
                           )
                         ),
                         SupervisedDescription(
                           projection1,
                           PersistentSingleNode,
                           0,
                           ExecutionStatus.Completed,
                           expectedProgress
                         )
                       )
                     )
    } yield ()
  }

}
