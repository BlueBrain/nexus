package ch.epfl.bluebrain.nexus.delta.sourcing.stream

import cats.effect.concurrent.Ref
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.EntityType
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

  implicit val patienceConfig: PatienceConfig = PatienceConfig(100.millis, 10.millis)

  override def munitFixtures: Seq[AnyFixture[_]] = List(supervisor3_1)

  private lazy val (sv, projectionStore) = supervisor3_1()
  // name1 should run on the node with index 1 in a 3-node cluster
  private val projection1 = ProjectionMetadata("test", "name1", None, None)
  // name2 should NOT run on the node with index 1 of a 3-node cluster
  private val projection2 = ProjectionMetadata("test", "name2", None, None)

  private def evalStream(start: Task[Unit]) =
    (_: Offset) =>
      Stream.eval(start) >> Stream.range(1, 21)
        .map { value => SuccessElem(EntityType("entity"), "id", Instant.EPOCH, Offset.at(value.toLong), ()) }

  test("Ignore a projection when it is meant to run on another node") {
      for {
        flag      <- Ref.of[Task, Boolean](false)
        projection =
          CompiledProjection.fromStream(projection2, ExecutionStrategy.TransientSingleNode, evalStream(flag.set(true)))
        _    <- sv.run(projection).assert(ExecutionStatus.Ignored)
        _    <- Task.sleep(100.millis)
        _    <- projectionStore.offset(projection2.name).assertNone
        _    <- flag.get.assert(false)
      } yield ()
  }

  test("Describe an ignored projection") {
    sv.describe(projection2.name).assertSome(
      SupervisedDescription(
        projection2,
        ExecutionStrategy.TransientSingleNode,
        0,
        ExecutionStatus.Ignored,
        ProjectionProgress.NoProgress
      )
    )
  }

  test("Destroy an ignored projection") {
    sv.destroy(projection2.name).assertSome(ExecutionStatus.Ignored)
  }

  test("Destroy an unknown projection") {
    sv.destroy("""xxx""").assertNone
  }

  test("Run a projection when it is meant to run on every node") {
    for {
      flag      <- Ref.of[Task, Boolean](false)
      projection =
        CompiledProjection.fromStream(projection2, ExecutionStrategy.EveryNode, evalStream(flag.set(true)))
      _    <- sv.run(projection).eventually(ExecutionStatus.Running)
      _   <- flag.get.eventually(true)
      _   <- sv.describe(projection2.name).eventuallySome(
        SupervisedDescription(
          projection2,
          ExecutionStrategy.EveryNode,
          0,
          ExecutionStatus.Completed,
          ProjectionProgress(
            Offset.at(20L),
            Instant.EPOCH,
            20,
            0,
            0
          )
        )
      )
      _    <- projectionStore.offset(projection2.name).assertNone
    } yield ()
  }

  test("Destroy a projection running on every node") {
    sv.destroy(projection2.name).assertSome(ExecutionStatus.Stopped)
  }

  test("Run a transient projection when it is meant to run on this node") {
      for {
        flag      <- Ref.of[Task, Boolean](false)
        projection =
          CompiledProjection.fromStream(projection1, ExecutionStrategy.TransientSingleNode, evalStream(flag.set(true)))
        _   <- sv.run(projection).eventually(ExecutionStatus.Running)
        _   <- flag.get.eventually(true)
        _   <- sv.describe(projection1.name).eventuallySome(
          SupervisedDescription(
            projection1,
            ExecutionStrategy.TransientSingleNode,
            0,
            ExecutionStatus.Completed,
            ProjectionProgress(
              Offset.at(20L),
              Instant.EPOCH,
              20,
              0,
              0
            )
          )
        )
        _    <- projectionStore.offset(projection1.name).assertNone
      } yield ()
  }

  test("Destroy a registered transient projection") {
    for {
      _ <- sv.destroy(projection1.name).assertSome(ExecutionStatus.Stopped)
      _ <- sv.describe(projection1.name).assertNone
    } yield ()
  }

  test("Run a persistent projection when it is meant to run on this node") {
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
      _    <- sv.run(projection).eventually(ExecutionStatus.Running)
      _   <- flag.get.eventually(true)
      _   <- sv.describe(projection1.name).eventuallySome(
        SupervisedDescription(
          projection1,
          PersistentSingleNode,
          0,
          ExecutionStatus.Completed,
          expectedProgress
        )
      )
      _    <- projectionStore.offset(projection1.name).assertSome(expectedProgress)
    } yield ()
  }

  test("Destroy a registered persistent projection") {
    for {
      _ <- sv.destroy(projection1.name).assertSome(ExecutionStatus.Stopped)
      _ <- sv.describe(projection1.name).assertNone
      _ <- projectionStore.offset(projection1.name).assertNone
    } yield ()
  }

  test("Should restart a failing projection") {
    val expectedException = new IllegalStateException("The stream crashed unexpectedly.")
    for {
      flag      <- Ref.of[Task, Boolean](false)
      _ <- projectionStore.offset(projection1.name).assertNone
      projection =
        CompiledProjection.fromStream(projection1, ExecutionStrategy.TransientSingleNode, evalStream(flag.set(true)).map(_ >> Stream.raiseError[Task](expectedException)))
      _     <- sv.run(projection).eventually(ExecutionStatus.Running)
      _     <- flag.get.eventually(true)
      _  <- sv.describe(projection1.name).map(_.exists(_.restarts > 0)).eventually(true)
    } yield ()
  }

  test("Destroy a failing projection") {
    for {
      _ <- sv.destroy(projection1.name).assertSome(ExecutionStatus.Stopped)
      _ <- sv.describe(projection1.name).eventuallyNone
      _ <- projectionStore.offset(projection1.name).assertNone
    } yield ()
  }

}
