package ch.epfl.bluebrain.nexus.kg.async

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

import akka.actor.Props
import akka.testkit.DefaultTimeout
import ch.epfl.bluebrain.nexus.admin.index.ProjectCache
import ch.epfl.bluebrain.nexus.admin.projects.{Project, ProjectResource}
import ch.epfl.bluebrain.nexus.admin.types.ResourceF
import ch.epfl.bluebrain.nexus.iam.types.Identity.Anonymous
import ch.epfl.bluebrain.nexus.kg.TestHelper
import ch.epfl.bluebrain.nexus.kg.resources.{Event, OrganizationRef}
import ch.epfl.bluebrain.nexus.delta.config.Settings
import ch.epfl.bluebrain.nexus.sourcing.projections.{ProjectionProgress, Projections, StreamSupervisor}
import ch.epfl.bluebrain.nexus.util.ActorSystemFixture
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

  implicit private val appConfig        = Settings(system).appConfig
  implicit private val keyValueStoreCfg = appConfig.keyValueStore.keyValueStoreConfig
  implicit private val http             = appConfig.http
  private val projectCache              = ProjectCache[Task]

  "A ProjectAttributesCoordinator" should {
    val orgUuid  = genUUID
    // format: off
    val project = ResourceF(genIri, genUUID, 1L, deprecated = false, Set.empty, Instant.EPOCH, Anonymous, Instant.EPOCH, Anonymous, Project("some-project", orgUuid, "some-org", None, Map.empty, genIri, genIri))
    val project2 = ResourceF(genIri, genUUID, 1L, deprecated = false, Set.empty, Instant.EPOCH, Anonymous, Instant.EPOCH, Anonymous,Project("some-project2", orgUuid, "some-org", None, Map.empty, genIri, genIri))
    // format: on

    val counterStart = new AtomicInteger(0)

    val coordinator1         = mock[StreamSupervisor[Task, ProjectionProgress]]
    val coordinator2         = mock[StreamSupervisor[Task, ProjectionProgress]]
    implicit val projections = mock[Projections[Task, Event]]

    val coordinatorProps = Props(
      new ProjectAttributesCoordinatorActor {
        override def startCoordinator(
            proj: ProjectResource,
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
      projectCache.replace(project.uuid, project).runToFuture.futureValue
      projectCache.replace(project2.uuid, project2).runToFuture.futureValue

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
