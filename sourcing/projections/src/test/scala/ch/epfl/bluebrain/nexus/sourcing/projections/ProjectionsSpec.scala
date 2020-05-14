package ch.epfl.bluebrain.nexus.sourcing.projections

import akka.actor.ActorSystem
import akka.persistence.query.Offset
import akka.stream.scaladsl.Source
import akka.testkit.{TestKit, TestKitBase}
import cats.effect.{ContextShift, IO}
import ch.epfl.bluebrain.nexus.sourcing.projections.Fixture.memoize
import ch.epfl.bluebrain.nexus.sourcing.projections.ProjectionProgress._
import ch.epfl.bluebrain.nexus.sourcing.projections.ProjectionsSpec.SomeEvent
import io.circe.generic.auto._
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.{BeforeAndAfterAll, DoNotDiscover}

import scala.concurrent.duration._

//noinspection TypeAnnotation
@DoNotDiscover
class ProjectionsSpec
    extends TestKitBase
    with AnyWordSpecLike
    with Matchers
    with TestHelpers
    with IOValues
    with Eventually
    with BeforeAndAfterAll {

  implicit override lazy val system: ActorSystem      = SystemBuilder.persistence("ProjectionsSpec")
  implicit private val contextShift: ContextShift[IO] = IO.contextShift(system.dispatcher)

  override protected def afterAll(): Unit = {
    TestKit.shutdownActorSystem(system)
    super.afterAll()
  }

  "A Projection" should {
    val id            = genString()
    val persistenceId = s"/some/${genString()}"
    val projections   = memoize(Projections[IO, SomeEvent]).unsafeRunSync()
    val progress      = OffsetProgress(Offset.sequence(42), 42, 42, 0)

    "store progress" in {
      projections.ioValue.recordProgress(id, progress).ioValue
    }

    "retrieve stored progress" in {
      projections.ioValue.progress(id).ioValue shouldEqual progress
    }

    "retrieve NoProgress for unknown projections" in {
      projections.ioValue.progress(genString()).ioValue shouldEqual NoProgress
    }

    val firstOffset: Offset  = Offset.sequence(42)
    val secondOffset: Offset = Offset.sequence(98)
    val firstEvent           = SomeEvent(1L, "description")
    val secondEvent          = SomeEvent(2L, "description2")

    "store an event" in {
      projections.ioValue.recordFailure(id, persistenceId, 1L, firstOffset, firstEvent).ioValue
    }

    "store another event" in {
      projections.ioValue.recordFailure(id, persistenceId, 2L, secondOffset, secondEvent).ioValue
    }

    "retrieve stored events" in {
      val expected = Seq((firstEvent, firstOffset), (secondEvent, secondOffset))
      eventually {
        logOf(projections.ioValue.failures(id)) should contain theSameElementsInOrderAs expected
      }
    }

    "retrieve empty list of events for unknown failure log" in {
      eventually {
        logOf(projections.ioValue.failures(genString())) shouldBe empty
      }
    }

  }

  private def logOf(source: Source[(SomeEvent, Offset), _]): Vector[(SomeEvent, Offset)] = {
    val f = source.runFold(Vector.empty[(SomeEvent, Offset)])(_ :+ _)
    IO.fromFuture(IO(f)).ioValue
  }

  implicit override def patienceConfig: PatienceConfig =
    PatienceConfig(30.seconds, 50.milliseconds)
}

object ProjectionsSpec {
  final case class SomeEvent(rev: Long, description: String)
}
