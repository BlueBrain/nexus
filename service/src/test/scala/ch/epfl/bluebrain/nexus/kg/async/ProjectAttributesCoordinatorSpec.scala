package ch.epfl.bluebrain.nexus.kg.async

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

import akka.actor.Props
import akka.testkit.DefaultTimeout
import ch.epfl.bluebrain.nexus.admin.client.types._
import ch.epfl.bluebrain.nexus.commons.test.ActorSystemFixture
import ch.epfl.bluebrain.nexus.kg.TestHelper
import ch.epfl.bluebrain.nexus.kg.cache._
import ch.epfl.bluebrain.nexus.kg.config.AppConfig._
import ch.epfl.bluebrain.nexus.kg.config.Settings
import ch.epfl.bluebrain.nexus.kg.resources.{Event, OrganizationRef}
import ch.epfl.bluebrain.nexus.sourcing.projections.{ProjectionProgress, Projections, StreamSupervisor}
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import org.mockito.{ArgumentMatchersSugar, IdiomaticMockito}
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.duration._

class ProjectAttributesCoordinatorSpec
    extends ActorSystemFixture("ProjectAttributesCoordinatorSpec", true)
    with TestHelper
    with DefaultTimeout
    with AnyWordSpecLike
    with Matchers
    with Eventually
    with ScalaFutures
    with IdiomaticMockito
    with ArgumentMatchersSugar {

  implicit override def patienceConfig: PatienceConfig = PatienceConfig(15.second, 150.milliseconds)

  implicit private val appConfig = Settings(system).appConfig
  private val projectCache       = ProjectCache[Task]

  "A ProjectAttributesCoordinator" should {
    val creator = genIri

    val orgUuid  = genUUID
    // format: off
    val project = Project(genIri, "some-project", "some-org", None, genIri, genIri, Map.empty, genUUID, orgUuid, 1L, deprecated = false, Instant.EPOCH, creator, Instant.EPOCH, creator)
    val project2 = Project(genIri, "some-project2", "some-org", None, genIri, genIri, Map.empty, genUUID, orgUuid, 1L, deprecated = false, Instant.EPOCH, creator, Instant.EPOCH, creator)
    // format: on

    val counterStart = new AtomicInteger(0)

    val coordinator1         = mock[StreamSupervisor[Task, ProjectionProgress]]
    val coordinator2         = mock[StreamSupervisor[Task, ProjectionProgress]]
    implicit val projections = mock[Projections[Task, Event]]

    val coordinatorProps = Props(
      new ProjectAttributesCoordinatorActor {
        override def startCoordinator(
            proj: Project,
            restartOffset: Boolean
        ): StreamSupervisor[Task, ProjectionProgress] = {
          counterStart.incrementAndGet()
          if (proj == project) coordinator1
          else if (proj == project2) coordinator2
          else throw new RuntimeException()
        }
      }
    )

    val coordinatorRef = ProjectAttributesCoordinatorActor.start(coordinatorProps, None, 1)
    val coordinator    = new ProjectAttributesCoordinator[Task](projectCache, coordinatorRef)

    projections.progress(any[String]) shouldReturn Task.pure(ProjectionProgress.NoProgress)

    "start attributes computation on initialize projects" in {
      projectCache.replace(project).runToFuture.futureValue
      projectCache.replace(project2).runToFuture.futureValue

      coordinator.start(project).runToFuture.futureValue
      eventually(counterStart.get shouldEqual 1)

      coordinator.start(project2).runToFuture.futureValue
      eventually(counterStart.get shouldEqual 2)
    }

    "ignore attempt to start again the attributes computation" in {
      coordinator.start(project).runToFuture.futureValue
      eventually(counterStart.get shouldEqual 2)

      coordinator.start(project2).runToFuture.futureValue
      eventually(counterStart.get shouldEqual 2)
    }

    "stop all related attributes computations when organization is deprecated" in {
      coordinator1.stop() shouldReturn Task.unit
      coordinator2.stop() shouldReturn Task.unit
      coordinator.stop(OrganizationRef(orgUuid)).runToFuture.futureValue
      eventually(counterStart.get shouldEqual 2)
      eventually(coordinator1.stop() wasCalled once)
      eventually(coordinator2.stop() wasCalled once)
    }

    "restart attributes computations" in {
      coordinator.start(project).runToFuture.futureValue
      eventually(counterStart.get shouldEqual 3)

      coordinator.start(project2).runToFuture.futureValue
      eventually(counterStart.get shouldEqual 4)
    }
  }
}
