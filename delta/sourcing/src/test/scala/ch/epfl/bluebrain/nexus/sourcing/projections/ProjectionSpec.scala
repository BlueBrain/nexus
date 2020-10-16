package ch.epfl.bluebrain.nexus.sourcing.projections

import akka.persistence.query.Offset
import ch.epfl.bluebrain.nexus.sourcing.projections.ProjectionId.ViewProjectionId
import ch.epfl.bluebrain.nexus.sourcing.projections.ProjectionProgress.NoProgress
import ch.epfl.bluebrain.nexus.testkit.{ShouldMatchers, TestHelpers}
import monix.bio.Task
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers.{contain, empty}
import org.scalatest.wordspec.AnyWordSpecLike

trait ProjectionSpec extends AnyWordSpecLike with BeforeAndAfterAll with TestHelpers with ShouldMatchers {

  import monix.execution.Scheduler.Implicits.global

  def configureSchema: Task[Unit]

  def projections: Projection[SomeEvent]

  override def beforeAll(): Unit = {
    super.beforeAll()
    configureSchema.runSyncUnsafe()
  }

  override def afterAll(): Unit = {
    super.afterAll()
  }

  "A Projection" should {
    val id              = ViewProjectionId(genString())
    val persistenceId   = s"/some/${genString()}"
    val progress        = ProjectionProgress(Offset.sequence(42), 42, 42, 0)
    val progressUpdated = ProjectionProgress(Offset.sequence(888), 888, 888, 0)

    "store and retrieve progress" in {
      val task = for {
        _           <- projections.recordProgress(id, progress)
        read        <- projections.progress(id)
        _           <- projections.recordProgress(id, progressUpdated)
        readUpdated <- projections.progress(id)
      } yield (read, readUpdated)

      task.runSyncUnsafe() shouldBe ((progress, progressUpdated))
    }

    "retrieve NoProgress for unknown projections" in {
      projections
        .progress(ViewProjectionId(genString()))
        .runSyncUnsafe() shouldBe NoProgress
    }

    val firstOffset: Offset  = Offset.sequence(42)
    val secondOffset: Offset = Offset.sequence(98)
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
        failures <- projections.failures(id).compile.toVector
      } yield failures

      val expected = Seq(
        (firstEvent, firstOffset, "IllegalArgumentException"),
        (secondEvent, secondOffset, "IllegalArgumentException")
      )
      task.runSyncUnsafe() should contain theSameElementsInOrderAs expected
    }

    "retrieve no failures for an unknown projection" in {
      val task = projections.failures(ViewProjectionId(genString())).compile.toVector
      task.runSyncUnsafe() shouldBe empty
    }
  }

}
