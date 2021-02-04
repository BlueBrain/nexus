package ch.epfl.bluebrain.nexus.sourcing.projections

import akka.persistence.query.{NoOffset, Offset}
import ch.epfl.bluebrain.nexus.sourcing.projections.ProjectionError.{ProjectionFailure, ProjectionWarning}
import ch.epfl.bluebrain.nexus.sourcing.projections.ProjectionId.ViewProjectionId
import ch.epfl.bluebrain.nexus.sourcing.projections.ProjectionProgress.NoProgress
import ch.epfl.bluebrain.nexus.sourcing.projections.RunResult.Warning
import ch.epfl.bluebrain.nexus.testkit.{IOFixedClock, ShouldMatchers, TestHelpers}
import org.scalatest.matchers.should.Matchers.{contain, empty}
import org.scalatest.wordspec.AnyWordSpecLike

import java.time.Instant

trait ProjectionSpec extends AnyWordSpecLike with IOFixedClock with TestHelpers with ShouldMatchers {

  import monix.execution.Scheduler.Implicits.global

  def projections: Projection[SomeEvent]

  def throwableToString(t: Throwable): String = t.getMessage

  def generateOffset: Offset

  "A Projection" should {
    val id              = ViewProjectionId(genString())
    val persistenceId   = s"/some/${genString()}"
    val init            = ProjectionProgress(NoOffset, Instant.EPOCH, 2, 2, 0, 0, SomeEvent(2, "initial"))
    val progress        = ProjectionProgress(generateOffset, Instant.EPOCH, 42, 42, 1, 0, SomeEvent(42, "p1"))
    val progressUpdated = ProjectionProgress(generateOffset, Instant.EPOCH, 888, 888, 1, 0, SomeEvent(888, "p2"))

    "store and retrieve progress" in {
      val task = for {
        _           <- projections.recordProgress(id, init)
        _           <- projections.recordProgress(id, progress)
        read        <- projections.progress(id)
        _           <- projections.recordProgress(id, progressUpdated)
        readUpdated <- projections.progress(id)
      } yield (init, read, readUpdated)

      task.runSyncUnsafe() shouldBe ((init, progress, progressUpdated))
    }

    "retrieve NoProgress for unknown projections" in {
      projections
        .progress(ViewProjectionId(genString()))
        .runSyncUnsafe() shouldBe NoProgress(SomeEvent.empty)
    }

    val firstOffset: Offset  = NoOffset
    val secondOffset: Offset = generateOffset
    val thirdOffset: Offset  = generateOffset
    val firstEvent           = SomeEvent(1L, "description")
    val secondEvent          = SomeEvent(2L, "description2")

    "store and retrieve warnings failures for events" in {

      val task = for {
        _        <- projections.recordWarnings(
                      id,
                      SuccessMessage(firstOffset, persistenceId, 1L, firstEvent, Vector(Warning("!!!")))
                    )
        _        <- projections.recordFailure(
                      id,
                      FailureMessage(secondOffset, persistenceId, 2L, secondEvent, new IllegalArgumentException("Error"))
                    )
        _        <- projections.recordFailure(
                      id,
                      CastFailedMessage(thirdOffset, persistenceId, 3L, "Class1", "Class2")
                    )
        failures <- projections.errors(id).compile.toVector
      } yield failures

      val expected = Seq(
        ProjectionWarning(firstOffset, Instant.EPOCH, "!!!", persistenceId, 1L, Some(firstEvent)),
        ProjectionFailure(
          secondOffset,
          Instant.EPOCH,
          "Error",
          persistenceId,
          2L,
          Some(secondEvent),
          "IllegalArgumentException"
        ),
        ProjectionFailure(
          thirdOffset,
          Instant.EPOCH,
          "Class 'Class1' was expected, 'Class2' was encountered.",
          persistenceId,
          3L,
          None,
          "ClassCastException"
        )
      )
      task.runSyncUnsafe() should contain theSameElementsAs expected
    }

    "retrieve no failures for an unknown projection" in {
      val task = projections.errors(ViewProjectionId(genString())).compile.toVector
      task.runSyncUnsafe() shouldBe empty
    }
  }

}
