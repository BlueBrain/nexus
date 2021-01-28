package ch.epfl.bluebrain.nexus.sourcing.projections

import akka.persistence.query.{NoOffset, Offset}
import ch.epfl.bluebrain.nexus.sourcing.projections.ProjectionId.ViewProjectionId
import ch.epfl.bluebrain.nexus.sourcing.projections.ProjectionProgress.NoProgress
import ch.epfl.bluebrain.nexus.testkit.{IOFixedClock, ShouldMatchers, TestHelpers}
import monix.bio.Task
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers.{contain, empty}
import org.scalatest.wordspec.AnyWordSpecLike

import java.time.Instant

trait ProjectionSpec
    extends AnyWordSpecLike
    with BeforeAndAfterAll
    with IOFixedClock
    with TestHelpers
    with ShouldMatchers {

  import monix.execution.Scheduler.Implicits.global

  def configureSchema: Task[Unit]

  def projections: Projection[SomeEvent]

  override def beforeAll(): Unit = {
    super.beforeAll()
    configureSchema.runSyncUnsafe()
  }

  def generateOffset: Offset

  override def afterAll(): Unit = {
    super.afterAll()
  }

  "A Projection" should {
    val id              = ViewProjectionId(genString())
    val persistenceId   = s"/some/${genString()}"
    val init            = ProjectionProgress(NoOffset, Instant.EPOCH, 2, 2, 0)
    val progress        = ProjectionProgress(generateOffset, Instant.EPOCH, 42, 42, 0)
    val progressUpdated = ProjectionProgress(generateOffset, Instant.EPOCH, 888, 888, 0)

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
        .runSyncUnsafe() shouldBe NoProgress
    }

    val firstOffset: Offset  = NoOffset
    val secondOffset: Offset = generateOffset
    val thirdOffset: Offset  = generateOffset
    val firstEvent           = SomeEvent(1L, "description")
    val secondEvent          = SomeEvent(2L, "description2")

    "store and retrieve failures for events" in {
      def throwableToString(t: Throwable) = t.getMessage
      val task                            = for {
        _        <- projections.recordFailure(
                      id,
                      FailureMessage(firstOffset, persistenceId, 1L, firstEvent, new IllegalArgumentException("Error")),
                      throwableToString
                    )
        _        <- projections.recordFailure(
                      id,
                      FailureMessage(secondOffset, persistenceId, 2L, secondEvent, new IllegalArgumentException("Error")),
                      throwableToString
                    )
        _        <- projections.recordFailure(
                      id,
                      CastFailedMessage(thirdOffset, persistenceId, 3L, "Class1", "Class2"),
                      throwableToString
                    )
        failures <- projections.failures(id).compile.toVector
      } yield failures

      val expected = Seq(
        ProjectionFailure(firstOffset, Instant.EPOCH, Some(firstEvent), "IllegalArgumentException"),
        ProjectionFailure(secondOffset, Instant.EPOCH, Some(secondEvent), "IllegalArgumentException"),
        ProjectionFailure(thirdOffset, Instant.EPOCH, None, "CastFailedMessage")
      )
      task.runSyncUnsafe() should contain theSameElementsAs expected
    }

    "retrieve no failures for an unknown projection" in {
      val task = projections.failures(ViewProjectionId(genString())).compile.toVector
      task.runSyncUnsafe() shouldBe empty
    }
  }

}
