package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.indexing

import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.permissions
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.CompositeViews
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.indexing.CompositeIntervalRestart.RemoteProjectsCounts
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeView.Interval
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewProjection.SparqlProjection
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewSource.{CrossProjectSource, ProjectSource, RemoteProjectSource}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.{CompositeView, SparqlConstructQuery}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.routes.CompositeViewsRoutes.RestartProjections
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.ProjectsCounts
import ch.epfl.bluebrain.nexus.delta.sdk.model.NonEmptySet
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectCountsCollection.ProjectCount
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{ProjectCountsCollection, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.ProjectionId.CompositeViewProjectionId
import ch.epfl.bluebrain.nexus.testkit.{EitherValuable, IOValues}
import io.circe.Json
import monix.bio.UIO
import monix.execution.Scheduler
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, OptionValues}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.time.Instant
import java.util.UUID
import scala.collection.mutable.{Map => MutableMap}
import scala.concurrent.duration._

class CompositeIntervalRestartSpec
    extends AnyWordSpecLike
    with Matchers
    with EitherValuable
    with BeforeAndAfterEach
    with BeforeAndAfterAll
    with IOValues
    with OptionValues {

  implicit private val s: Scheduler                                           = Scheduler.global
  private val localProject                                                    = ProjectRef.unsafe("org", "proj1")
  private val crossProject                                                    = ProjectRef.unsafe("org", "proj2")
  private val remoteProject                                                   = ProjectRef.unsafe("org", "proj3")
  private val initCount                                                       = ProjectCount(1, Instant.EPOCH)
  private val projectsCountsCache: MutableMap[ProjectRef, ProjectCount]       = MutableMap.empty
  private val remoteProjectsCountsCache: MutableMap[ProjectRef, ProjectCount] = MutableMap.empty
  private val restartProjectionsCache                                         = MutableMap.empty[(Iri, ProjectRef, Set[CompositeViewProjectionId]), Int]

  override protected def beforeEach(): Unit = {
    projectsCountsCache.clear()
    projectsCountsCache.addOne(localProject -> initCount).addOne(crossProject -> initCount)
    remoteProjectsCountsCache.clear()
    remoteProjectsCountsCache.addOne(remoteProject -> initCount)
    restartProjectionsCache.clear()
    super.beforeEach()
  }

  private val projectsCounts = new ProjectsCounts {
    override def get(): UIO[ProjectCountsCollection] =
      UIO.delay(ProjectCountsCollection(projectsCountsCache.toMap))

    override def get(project: ProjectRef): UIO[Option[ProjectCount]] =
      UIO.delay(projectsCountsCache.get(project))
  }

  private val remoteProjectsCounts: RemoteProjectsCounts = source =>
    UIO.delay(remoteProjectsCountsCache.get(source.project))

  private val restartProjections: RestartProjections =
    (iri, projectRef, projectionIds) =>
      UIO
        .delay(
          restartProjectionsCache.updateWith((iri, projectRef, projectionIds))(count => Some(count.fold(1)(_ + 1)))
        )
        .void

  private val intervalRestart     = CompositeIntervalRestart(projectsCounts, restartProjections, remoteProjectsCounts)
  private val projectSource       = ProjectSource(nxv + "s1", UUID.randomUUID(), Set.empty, Set.empty, None, false)
  private val crossProjectSource  = CrossProjectSource(
    nxv + "s2",
    UUID.randomUUID(),
    Set.empty,
    Set.empty,
    None,
    false,
    crossProject,
    Set.empty
  )
  private val remoteProjectSource = RemoteProjectSource(
    nxv + "s3",
    UUID.randomUUID(),
    Set.empty,
    Set.empty,
    None,
    false,
    remoteProject,
    "",
    None
  )
  private val construct           = SparqlConstructQuery(
    "prefix p: <http://localhost/>\nCONSTRUCT{ {resource_id} p:transformed ?v } WHERE { {resource_id} p:predicate ?v}"
  ).rightValue
  private val blazeProjection     =
    SparqlProjection(
      nxv + "blaze1",
      UUID.randomUUID(),
      construct,
      Set.empty,
      Set.empty,
      None,
      false,
      false,
      permissions.query
    )
  private val view                = CompositeView(
    iri"http://localhost/id",
    localProject,
    NonEmptySet.of(projectSource, crossProjectSource, remoteProjectSource),
    NonEmptySet.of(blazeProjection),
    Some(Interval(900.millis)),
    UUID.randomUUID(),
    Map.empty,
    Json.obj()
  )

  private val projectSourceProjection       = CompositeViews.projectionId(projectSource, blazeProjection, 1)._2
  private val crossProjectSourceProjection  = CompositeViews.projectionId(crossProjectSource, blazeProjection, 1)._2
  private val remoteProjectSourceProjection = CompositeViews.projectionId(remoteProjectSource, blazeProjection, 1)._2
  private val allProjectionsIds             =
    Set(projectSourceProjection, crossProjectSourceProjection, remoteProjectSourceProjection)

  private def restartedAllProjections(times: Int) =
    restartedProjections(allProjectionsIds, times)

  private def restartedProjections(projections: Set[CompositeViewProjectionId], times: Int) =
    MutableMap((view.id, view.project, projections) -> times)

  "A Composite interval" should {

    "check next interval" in {
      val cancelable = intervalRestart.run(view, 1).accepted
      val now        = Instant.now().toEpochMilli
      val timeDiff   = cancelable.nextRestart.accepted.value.toEpochMilli - now
      timeDiff should (be >= 800L and be <= 900L)
      cancelable.cancel()
    }

    "not restart if projection sources do not receive new events" in {
      val cancelable = intervalRestart.run(view, 1).accepted
      Thread.sleep(1 * 1000) // sleep 1 seconds
      restartProjectionsCache shouldEqual restartedAllProjections(times = 1)

      Thread.sleep(1 * 1000) // sleep 1 seconds
      restartProjectionsCache shouldEqual restartedAllProjections(times = 1)

      cancelable.cancel()
      Thread.sleep(1 * 1000) // sleep 1 seconds
      restartProjectionsCache shouldEqual restartedAllProjections(times = 1)
    }

    "restart the projections from sources that received new events" in {
      val modifiedCount = initCount.copy(value = initCount.value + 1)
      val cancelable    = intervalRestart.run(view, 1).accepted
      Thread.sleep(1 * 1000) // sleep 1 seconds

      val expected1 = restartedAllProjections(times = 1)
      restartProjectionsCache shouldEqual expected1

      projectsCountsCache.addOne(localProject -> modifiedCount).addOne(crossProject -> modifiedCount)
      Thread.sleep(1 * 1000) // sleep 1 seconds
      val expected2 = expected1 ++ restartedProjections(Set(projectSourceProjection, crossProjectSourceProjection), 1)
      restartProjectionsCache shouldEqual expected2

      remoteProjectsCountsCache.addOne(remoteProject -> modifiedCount)
      Thread.sleep(1 * 1000) // sleep 1 seconds
      val expected3 = expected2 ++ restartedProjections(Set(remoteProjectSourceProjection), 1)
      restartProjectionsCache shouldEqual expected3

      cancelable.cancel()
      Thread.sleep(1 * 1000) // sleep 1 seconds
      restartProjectionsCache shouldEqual expected3
    }
  }

}
