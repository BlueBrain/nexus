package ch.epfl.bluebrain.nexus.sourcingnew.projections

import akka.persistence.query.Offset
import cats.effect.{ContextShift, IO, Timer}
import ch.epfl.bluebrain.nexus.sourcingnew.projections.ProjectionProgress.NoProgress
import ch.epfl.bluebrain.nexus.testkit.{ShouldMatchers, TestHelpers}
import izumi.distage.testkit.scalatest.DistageSpecScalatest
import org.scalatest.matchers.should.Matchers.{contain, empty}

import scala.concurrent.ExecutionContext

abstract class ProjectionSpec extends DistageSpecScalatest[IO] with TestHelpers with ShouldMatchers {

  implicit protected val cs: ContextShift[IO] = IO.contextShift(ExecutionContext.global)
  implicit protected val tm: Timer[IO]        = IO.timer(ExecutionContext.global)

  "A Projection" should {
    val id = ViewProjectionId(genString())
    val persistenceId = s"/some/${genString()}"
    val progress = ProjectionProgress(Offset.sequence(42), 42, 42, 0)
    val progressUpdated = ProjectionProgress(Offset.sequence(888), 888, 888, 0)

    "store and retrieve progress" in {
      (projections: Projection[IO, SomeEvent],schemaManager: SchemaMigration[IO]) =>
        for {
          _     <- schemaManager.migrate()
          _ <- projections.recordProgress(id, progress)
          read <- projections.progress(id)
          _ = read shouldEqual progress
          _ <- projections.recordProgress(id, progressUpdated)
          readUpdated <- projections.progress(id)
          _ = readUpdated shouldEqual progressUpdated
        } yield ()
    }


    "retrieve NoProgress for unknown projections" in {
      (projections: Projection[IO, SomeEvent],schemaManager: SchemaMigration[IO]) =>
        for {
          _     <- schemaManager.migrate()
          read <- projections.progress(ViewProjectionId(genString()))
          _     = read shouldEqual NoProgress
        } yield ()
    }

    val firstOffset: Offset  = Offset.sequence(42)
    val secondOffset: Offset = Offset.sequence(98)
    val firstEvent           = SomeEvent(1L, "description")
    val secondEvent          = SomeEvent(2L, "description2")

    "store and retrieve failures for events" in {
      (projections: Projection[IO, SomeEvent],schemaManager: SchemaMigration[IO]) =>
        val expected                            = Seq((firstEvent, firstOffset, "Error"), (secondEvent, secondOffset, "Error"))
        def throwableToString(t: Throwable) = t.getMessage
        for {
          _     <- schemaManager.migrate()
          _   <- projections.recordFailure(id, FailureMessage(firstOffset, persistenceId, 1L,  firstEvent,
            new IllegalArgumentException("Error")), throwableToString)
          _   <- projections.recordFailure(id, FailureMessage(secondOffset, persistenceId, 2L, secondEvent,
            new IllegalArgumentException("Error")), throwableToString)
          log <- projections.failures(id).compile.toVector
          _    = log should contain theSameElementsInOrderAs expected
        } yield ()
    }

    "retrieve no failures for an unknown projection" in {
      (projections: Projection[IO, SomeEvent],schemaManager: SchemaMigration[IO]) =>
        for {
          _     <- schemaManager.migrate()
          log <- projections.failures(ViewProjectionId(genString())).compile.toVector
          _    = log shouldBe empty
        } yield ()
    }
  }

}
