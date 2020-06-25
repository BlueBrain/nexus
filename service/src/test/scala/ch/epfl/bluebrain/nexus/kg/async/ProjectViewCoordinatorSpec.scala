package ch.epfl.bluebrain.nexus.kg.async

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

import akka.actor.Props
import akka.persistence.query.{NoOffset, Sequence}
import akka.testkit.DefaultTimeout
import cats.implicits._
import ch.epfl.bluebrain.nexus.admin.client.types._
import ch.epfl.bluebrain.nexus.commons.cache.OnKeyValueStoreChange
import ch.epfl.bluebrain.nexus.commons.test.ActorSystemFixture
import ch.epfl.bluebrain.nexus.iam.client.types.Identity.{Anonymous, User}
import ch.epfl.bluebrain.nexus.iam.client.types.{AccessControlList, AccessControlLists}
import ch.epfl.bluebrain.nexus.kg.TestHelper
import ch.epfl.bluebrain.nexus.kg.archives.ArchiveCache
import ch.epfl.bluebrain.nexus.kg.async.ProjectViewCoordinatorActor.{onViewChange, ViewCoordinator}
import ch.epfl.bluebrain.nexus.kg.cache._
import ch.epfl.bluebrain.nexus.kg.config.KgConfig._
import ch.epfl.bluebrain.nexus.kg.config.Settings
import ch.epfl.bluebrain.nexus.kg.indexing.Statistics.{CompositeViewStatistics, ViewStatistics}
import ch.epfl.bluebrain.nexus.kg.indexing.View.CompositeView.Projection.{ElasticSearchProjection, SparqlProjection}
import ch.epfl.bluebrain.nexus.kg.indexing.View.CompositeView.Source.{CrossProjectEventStream, ProjectEventStream}
import ch.epfl.bluebrain.nexus.kg.indexing.View._
import ch.epfl.bluebrain.nexus.kg.indexing.{IdentifiedProgress, View}
import ch.epfl.bluebrain.nexus.kg.resources.ProjectIdentifier.ProjectRef
import ch.epfl.bluebrain.nexus.kg.resources.syntax._
import ch.epfl.bluebrain.nexus.kg.resources.{CompositeViewOffset, OrganizationRef}
import ch.epfl.bluebrain.nexus.rdf.Iri.AbsoluteIri
import ch.epfl.bluebrain.nexus.rdf.Iri.Path._
import ch.epfl.bluebrain.nexus.sourcing.projections.ProjectionProgress.{OffsetProgress, OffsetsProgress}
import ch.epfl.bluebrain.nexus.sourcing.projections.{ProjectionProgress, Projections, StreamSupervisor}
import com.typesafe.scalalogging.Logger
import io.circe.Json
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import org.mockito.{ArgumentMatchersSugar, IdiomaticMockito}
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.{Inspectors, OptionValues}

import scala.concurrent.duration._

class ProjectViewCoordinatorSpec
    extends ActorSystemFixture("ProjectViewCoordinatorSpec", true)
    with TestHelper
    with DefaultTimeout
    with AnyWordSpecLike
    with Matchers
    with Eventually
    with ScalaFutures
    with IdiomaticMockito
    with ArgumentMatchersSugar
    with Inspectors
    with OptionValues {

  implicit override def patienceConfig: PatienceConfig = PatienceConfig(15.second, 150.milliseconds)

  implicit private val appConfig    = Settings(system).appConfig
  implicit private val log: Logger  = Logger[this.type]
  implicit private val projectCache = ProjectCache[Task]
  private val viewCache             = ViewCache[Task]

  "A ProjectViewCoordinator" should {
    val creator = genIri

    val orgUuid             = genUUID
    // format: off
    implicit val project  = Project(genIri, "some-project", "some-org", None, genIri, genIri, Map.empty, genUUID, orgUuid, 1L, deprecated = false, Instant.EPOCH, creator, Instant.EPOCH, creator)
    val project2          = Project(genIri, "some-project2", "some-org", None, genIri, genIri, Map.empty, genUUID, genUUID, 1L, deprecated = false, Instant.EPOCH, creator, Instant.EPOCH, creator)
    val projectLabelUpdated = genString()
    val project2Updated   = project2.copy(label = projectLabelUpdated, rev = 2L)
    val view              = SparqlView(Filter(), true, project.ref, genIri, genUUID, 1L, deprecated = false)
    val view2             = ElasticSearchView(Json.obj(), Filter(Set(genIri)), true, true, project.ref, genIri, genUUID, 1L, deprecated = false)
    val view2Updated      = view2.copy(filter = view2.filter.copy(resourceSchemas = Set(genIri)), rev = 2L)
    val view3             = SparqlView(Filter(), true, project2.ref, genIri, genUUID, 1L, deprecated = false)
    val projection1       = ElasticSearchProjection("query", ElasticSearchView(Json.obj(), Filter(), false, false, project.ref, genIri, genUUID, 1L, false), Json.obj())
    val projection2       = SparqlProjection("query2", SparqlView(Filter(), true, project.ref, genIri, genUUID, 1L, false))
    val localS            = ProjectEventStream(genIri, Filter())
    val crossProjectS     = CrossProjectEventStream(genIri, Filter(), project2.ref, Set(Anonymous))
    val view4             = CompositeView(Set(localS, crossProjectS), Set(projection1, projection2), None, project.ref, genIri, genUUID, 1L, false)
    // format: on

    val counterStart            = new AtomicInteger(0)
    val counterStartProjections = new AtomicInteger(0)
    val counterStop             = new AtomicInteger(0)

    val coordinator1         = mock[StreamSupervisor[Task, ProjectionProgress]]
    val coordinator2         = mock[StreamSupervisor[Task, ProjectionProgress]]
    val coordinator2Updated  = mock[StreamSupervisor[Task, ProjectionProgress]]
    val coordinator3         = mock[StreamSupervisor[Task, ProjectionProgress]]
    val coordinator3Updated  = mock[StreamSupervisor[Task, ProjectionProgress]]
    val coordinator4         = mock[StreamSupervisor[Task, ProjectionProgress]]
    implicit val projections = mock[Projections[Task, String]]
    implicit val aclsCache   = mock[AclsCache[Task]]
    val offset1              = OffsetProgress(Sequence(1L), 2L, 3L, 4L)
    val offset2              = OffsetProgress(Sequence(2L), 3L, 4L, 5L)
    val offset3              = OffsetProgress(Sequence(3L), 4L, 5L, 6L)
    val offset4              = OffsetProgress(Sequence(4L), 5L, 6L, 7L)
    val offset5              = OffsetProgress(Sequence(5L), 6L, 7L, 8L)
    val offset6              = OffsetProgress(Sequence(6L), 7L, 8L, 9L)

    val progress: ProjectionProgress = OffsetsProgress(
      Map(
        localS.id.asString                                      -> offset1,
        crossProjectS.id.asString                               -> offset2,
        view4.progressId(localS.id, projection1.view.id)        -> offset3,
        view4.progressId(crossProjectS.id, projection1.view.id) -> offset4,
        view4.progressId(localS.id, projection2.view.id)        -> offset5,
        view4.progressId(crossProjectS.id, projection2.view.id) -> offset6
      )
    )

    coordinator1.stop() shouldReturn Task.unit
    coordinator2.stop() shouldReturn Task.unit
    coordinator2Updated.stop() shouldReturn Task.unit
    coordinator3.stop() shouldReturn Task.unit
    coordinator3Updated.stop() shouldReturn Task.unit
    coordinator4.stop() shouldReturn Task.unit

    val coordinatorProps = Props(
      new ProjectViewCoordinatorActor(viewCache) {
        override def startCoordinator(
            v: View.IndexedView,
            proj: Project,
            restart: Boolean,
            prevRestart: Option[Instant]
        ): ViewCoordinator = {
          counterStart.incrementAndGet()
          if (v == view && proj == project) ViewCoordinator(coordinator1)
          else if (v == view2 && proj == project) ViewCoordinator(coordinator2)
          else if (v == view2Updated && proj == project) ViewCoordinator(coordinator2Updated)
          else if (v == view3 && proj == project2) ViewCoordinator(coordinator3)
          else if (v == view3.copy(rev = 2L) && proj == project2) ViewCoordinator(coordinator3)
          else if (v == view3.copy(rev = 2L) && proj == project2Updated && restart)
            ViewCoordinator(coordinator3Updated)
          else if (v == view4 && proj == project) ViewCoordinator(coordinator4)
          else if (v == view4.copy(sources = Set(localS)) && proj == project) ViewCoordinator(coordinator4)
          else throw new RuntimeException()
        }

        override def startCoordinator(
            view: CompositeView,
            proj: Project,
            restartProgress: Set[String],
            prevRestart: Option[Instant]
        ): ViewCoordinator =
          if (view == view4 && proj == project) {
            counterStartProjections.incrementAndGet()
            ViewCoordinator(coordinator4)
          } else throw new RuntimeException()

        override def deleteViewIndices(view: View.IndexedView, project: Project): Task[Unit] = {
          counterStop.incrementAndGet()
          Task.unit
        }

        override def onChange(ref: ProjectRef): OnKeyValueStoreChange[AbsoluteIri, View] =
          onViewChange(ref, self)

      }
    )

    val coordinatorRef = ProjectViewCoordinatorActor.start(coordinatorProps, None, 1)
    val coordinator    =
      new ProjectViewCoordinator[Task](
        Caches(
          projectCache,
          viewCache,
          mock[ResolverCache[Task]],
          mock[StorageCache[Task]],
          mock[ArchiveCache[Task]]
        ),
        coordinatorRef
      )

    projections.progress(any[String]) shouldReturn Task.pure(ProjectionProgress.NoProgress)

    val currentStart     = new AtomicInteger(0)
    val currentProjStart = new AtomicInteger(0)
    val currentStop      = new AtomicInteger(0)

    "initialize projects" in {
      projectCache.replace(project).runToFuture.futureValue
      projectCache.replace(project2).runToFuture.futureValue

      coordinator.start(project).runToFuture.futureValue
      eventually(counterStart.get shouldEqual currentStart.get)

      coordinator.start(project2).runToFuture.futureValue
      eventually(counterStart.get shouldEqual currentStart.get)
    }

    "start view indexer when views are cached" in {
      viewCache.put(view2).runToFuture.futureValue
      currentStart.incrementAndGet()
      eventually(counterStart.get shouldEqual currentStart.get)

      currentStart.incrementAndGet()
      viewCache.put(view).runToFuture.futureValue
      eventually(counterStart.get shouldEqual currentStart.get)

      currentStart.incrementAndGet()
      viewCache.put(view3).runToFuture.futureValue
      eventually(counterStart.get shouldEqual currentStart.get)

      currentStart.incrementAndGet()
      aclsCache.list shouldReturn
        Task(AccessControlLists(/ -> resourceAcls(AccessControlList(Anonymous -> Set(read)))))
      viewCache.put(view4).runToFuture.futureValue
      eventually(counterStart.get shouldEqual currentStart.get)

      counterStartProjections.get shouldEqual currentProjStart.get
      counterStop.get shouldEqual counterStop.get
    }

    "fetch statistics" in {
      coordinator4.state() shouldReturn Task(Some(progress))
      val result = coordinator.statistics(view4.id).runToFuture.futureValue.value.asInstanceOf[CompositeViewStatistics]
      result.values.map(_.map(_ => ())) shouldEqual
        Set(IdentifiedProgress(localS.id, ()), IdentifiedProgress(crossProjectS.id, ()))
      result.processedEvents shouldEqual (offset1.processed + offset2.processed)
      result.discardedEvents shouldEqual (offset1.discarded + offset2.discarded)
      result.failedEvents shouldEqual (offset1.failed + offset2.failed)
    }

    "fetch projection statistics" in {
      coordinator4.state() shouldReturn Task(Some(progress))
      val stats  = coordinator.projectionStats(view4.id, projection1.view.id).runToFuture.futureValue
      val result = stats.value.asInstanceOf[CompositeViewStatistics]

      result.values.map(_.map(_ => ())) shouldEqual
        Set(
          IdentifiedProgress(localS.id, projection1.view.id, ()),
          IdentifiedProgress(crossProjectS.id, projection1.view.id, ())
        )
      result.processedEvents shouldEqual (offset3.processed + offset4.processed)
      result.discardedEvents shouldEqual (offset3.discarded + offset4.discarded)
      result.failedEvents shouldEqual (offset3.failed + offset4.failed)
    }

    "fetch all projection statistics" in {
      coordinator4.state() shouldReturn Task(Some(progress))
      val result = coordinator.projectionStats(view4.id).runToFuture.futureValue.value

      result.map(_.map(_ => ())) shouldEqual
        Set(
          IdentifiedProgress(localS.id, projection1.view.id, ()),
          IdentifiedProgress(crossProjectS.id, projection1.view.id, ()),
          IdentifiedProgress(localS.id, projection2.view.id, ()),
          IdentifiedProgress(crossProjectS.id, projection2.view.id, ())
        )
    }

    "fetch source statistics" in {
      coordinator4.state() shouldReturn Task(Some(progress))
      val stats  = coordinator.sourceStat(view4.id, localS.id).runToFuture.futureValue
      val result = stats.value.asInstanceOf[ViewStatistics]

      result.processedEvents shouldEqual offset1.processed
      result.discardedEvents shouldEqual offset1.discarded
      result.failedEvents shouldEqual offset1.failed
    }

    "fetch all source statistics" in {
      coordinator4.state() shouldReturn Task(Some(progress))
      val result = coordinator.sourceStats(view4.id).runToFuture.futureValue.value

      result.map(_.map(_ => ())) shouldEqual
        Set(
          IdentifiedProgress(localS.id, ()),
          IdentifiedProgress(crossProjectS.id, ())
        )
    }

    "fetch statistics return None when coordinator not present" in {
      coordinator3.state() shouldReturn Task(None)
      coordinator.statistics(view3.id).runToFuture.futureValue shouldEqual None
      coordinator.projectionStats(view3.id).runToFuture.futureValue shouldEqual None
      coordinator.projectionStats(view3.id, genIri).runToFuture.futureValue shouldEqual None
    }

    "fetch offset" in {
      coordinator4.state() shouldReturn Task(Some(progress))
      coordinator.offset(view4.id).runToFuture.futureValue.value shouldEqual
        CompositeViewOffset(
          Set(IdentifiedProgress(localS.id, offset1.offset), (IdentifiedProgress(crossProjectS.id, offset2.offset)))
        )
    }

    "fetch all projections offset" in {
      coordinator4.state() shouldReturn Task(Some(progress))
      val result = coordinator.projectionOffsets(view4.id).runToFuture.futureValue.value
      result.iterator.size shouldEqual 4L
      result shouldEqual Set(
        IdentifiedProgress(localS.id, projection1.view.id, offset3.offset),
        IdentifiedProgress(crossProjectS.id, projection1.view.id, offset4.offset),
        IdentifiedProgress(localS.id, projection2.view.id, offset5.offset),
        IdentifiedProgress(crossProjectS.id, projection2.view.id, offset6.offset)
      )
    }

    "fetch projections offset" in {
      coordinator4.state() shouldReturn Task(Some(progress))
      val offset = coordinator.projectionOffset(view4.id, projection2.view.id).runToFuture.futureValue.value
      offset shouldEqual CompositeViewOffset(
        Set(
          IdentifiedProgress(localS.id, projection2.view.id, offset5.offset),
          IdentifiedProgress(crossProjectS.id, projection2.view.id, offset6.offset)
        )
      )
    }

    "fetch offset return None when coordinator not present" in {
      coordinator4.state() shouldReturn Task(None)
      coordinator.offset(view4.id).runToFuture.futureValue.value shouldEqual CompositeViewOffset(
        Set(
          IdentifiedProgress(localS.id, NoOffset),
          IdentifiedProgress(crossProjectS.id, NoOffset)
        )
      )
      coordinator.projectionOffsets(view4.id).runToFuture.futureValue.value shouldEqual
        Set(
          IdentifiedProgress(localS.id, projection1.view.id, NoOffset),
          IdentifiedProgress(crossProjectS.id, projection1.view.id, NoOffset),
          IdentifiedProgress(localS.id, projection2.view.id, NoOffset),
          IdentifiedProgress(crossProjectS.id, projection2.view.id, NoOffset)
        )
      coordinator.projectionOffset(view4.id, genIri).runToFuture.futureValue shouldEqual None
    }

    "trigger manual view restart" in {
      coordinator.restart(view.id).runToFuture.futureValue
      currentStart.incrementAndGet()
      eventually(coordinator1.stop() wasCalled once)
      eventually(counterStart.get shouldEqual currentStart.get)
      eventually(counterStop.get shouldEqual currentStop.get)
      counterStartProjections.get shouldEqual currentProjStart.get
    }

    "trigger manual projections restart" in {
      coordinator.restartProjections(view4.id).runToFuture.futureValue
      currentProjStart.incrementAndGet()
      eventually(coordinator4.stop() wasCalled once)
      eventually(counterStartProjections.get shouldEqual currentProjStart.get)
      counterStart.get shouldEqual currentStart.get
      counterStop.get shouldEqual currentStop.get
    }

    "stop view when view is removed (deprecated) from the cache" in {
      viewCache.put(view.copy(deprecated = true)).runToFuture.futureValue
      currentStop.incrementAndGet()
      eventually(coordinator1.stop() wasCalled twice)
      eventually(counterStop.get shouldEqual currentStop.get)
      eventually(counterStart.get shouldEqual currentStart.get)
      counterStartProjections.get shouldEqual currentProjStart.get
    }

    "stop old elasticsearch view start new view when current view updated" in {
      viewCache.put(view2Updated).runToFuture.futureValue
      currentStop.incrementAndGet()
      eventually(counterStop.get shouldEqual currentStop.get)

      currentStart.incrementAndGet()
      eventually(coordinator2.stop() wasCalled once)
      eventually(counterStart.get shouldEqual currentStart.get)

      counterStartProjections.get shouldEqual currentProjStart.get
    }

    "stop old sparql view start new view when current view updated" in {
      viewCache.put(view3.copy(rev = 2L)).runToFuture.futureValue
      currentStop.incrementAndGet()
      eventually(counterStop.get shouldEqual currentStop.get)

      currentStart.incrementAndGet()
      eventually(coordinator3.stop() wasCalled once)
      eventually(counterStart.get shouldEqual currentStart.get)
      counterStartProjections.get shouldEqual currentProjStart.get
    }

    "stop all related views when organization is deprecated" in {
      coordinator.stop(OrganizationRef(orgUuid)).runToFuture.futureValue
      eventually(coordinator2Updated.stop() wasCalled once)
      eventually(counterStop.get shouldEqual currentStop.get)
      eventually(counterStart.get shouldEqual currentStart.get)
      counterStartProjections.get shouldEqual currentProjStart.get
    }

    "restart all related views when project changes" in {
      projectCache.replace(project2Updated).runToFuture.futureValue
      coordinator.change(project2Updated, project2).runToFuture.futureValue
      currentStop.incrementAndGet()
      eventually(counterStop.get shouldEqual currentStop.get)
      currentStart.incrementAndGet()
      eventually(counterStart.get shouldEqual currentStart.get)
      eventually(coordinator3.stop() wasCalled twice)
      counterStartProjections.get shouldEqual currentProjStart.get
    }

    "stop related views when project is deprecated" in {
      projectCache.replace(project2Updated.copy(deprecated = true)).runToFuture.futureValue
      coordinator.stop(project2Updated.ref).runToFuture.futureValue
      eventually(counterStop.get shouldEqual currentStop.get)
      eventually(counterStart.get shouldEqual currentStart.get)
      eventually(coordinator3Updated.stop() wasCalled once)
      counterStartProjections.get shouldEqual currentProjStart.get
    }

    "restart CompositeView on ACLs change" in {
      val projectPath = project.organizationLabel / project.label
      val acls        = AccessControlLists(
        projectPath -> resourceAcls(AccessControlList(Anonymous -> Set(read, write))),
        /           -> resourceAcls(AccessControlList(User(genString(), genString()) -> Set(read, write)))
      )

      coordinator.changeAcls(acls, project).runToFuture.futureValue
      eventually(coordinator4.stop() wasCalled twice)
      currentStart.incrementAndGet()
      eventually(counterStart.get shouldEqual currentStart.get)
    }

    "do nothing when the ACL changes do not affect the triggered project" in {
      val projectPath = project.organizationLabel / project.label
      val acls        = AccessControlLists(
        projectPath -> resourceAcls(AccessControlList(Anonymous -> Set(read, write))),
        /           -> resourceAcls(AccessControlList(User(genString(), genString()) -> Set(read, write)))
      )

      coordinator.changeAcls(acls, project2).runToFuture.futureValue
      coordinator4.stop() wasCalled twice
      counterStart.get shouldEqual currentStart.get
    }

    "resolve projects from path" in {
      val paths = List(
        /                           -> Set(project, project2Updated),
        / + "some-org"              -> Set(project, project2Updated),
        "some-org" / "some-project" -> Set(project)
      )
      forAll(paths) {
        case (path, projects) => path.resolveProjects.runToFuture.futureValue.toSet shouldEqual projects
      }
    }
  }
}
