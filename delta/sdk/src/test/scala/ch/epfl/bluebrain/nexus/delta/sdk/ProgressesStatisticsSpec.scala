package ch.epfl.bluebrain.nexus.delta.sdk

import akka.persistence.query.{NoOffset, Sequence}
import ch.epfl.bluebrain.nexus.delta.sdk.cache.KeyValueStore
import ch.epfl.bluebrain.nexus.delta.sdk.model.ProgressStatistics
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectCountsCollection.ProjectCount
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{ProjectCountsCollection, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.ProjectionId.CacheProjectionId
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.{ProjectionId, ProjectionProgress}
import ch.epfl.bluebrain.nexus.testkit.IOValues
import monix.bio.UIO
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.time.Instant

class ProgressesStatisticsSpec extends AnyWordSpecLike with Matchers with IOValues {

  "ProgressesStatistics" should {
    val project      = ProjectRef.unsafe("org", "project")
    val now          = Instant.now()
    val nowMinus5    = now.minusSeconds(5)
    val projectStats = ProjectCount(10, 10, now)

    val projectsCounts = new ProjectsCounts {
      override def get(): UIO[ProjectCountsCollection]                 =
        UIO(ProjectCountsCollection(Map(project -> projectStats)))
      override def get(project: ProjectRef): UIO[Option[ProjectCount]] = get().map(_.get(project))
    }

    val progressesCache = KeyValueStore.localLRU[ProjectionId, ProjectionProgress[Unit]](10L).accepted
    val stats           = new ProgressesStatistics(progressesCache, projectsCounts)

    "fetch statistics for a projection" in {
      val projectionId = CacheProjectionId("statistic")
      val progress     = ProjectionProgress(Sequence(10), nowMinus5, 9, 1, 0, 1)
      progressesCache.put(projectionId, progress).accepted
      stats.statistics(project, projectionId).accepted shouldEqual ProgressStatistics(
        processedEvents = 9,
        discardedEvents = 1,
        failedEvents = 1,
        evaluatedEvents = 7,
        remainingEvents = 1,
        totalEvents = 10,
        lastEventDateTime = Some(now),
        lastProcessedEventDateTime = Some(nowMinus5),
        delayInSeconds = Some(5)
      )
    }

    "fetch updated statistics for a projection" in {
      val projectionId = CacheProjectionId("statistic")
      val progress     = ProjectionProgress(Sequence(11), now, 10, 1, 0, 1)
      progressesCache.put(projectionId, progress).accepted
      stats.statistics(project, projectionId).accepted shouldEqual ProgressStatistics(
        processedEvents = 10,
        discardedEvents = 1,
        failedEvents = 1,
        evaluatedEvents = 8,
        remainingEvents = 0,
        totalEvents = 10,
        lastEventDateTime = Some(now),
        lastProcessedEventDateTime = Some(now),
        delayInSeconds = Some(0)
      )
    }

    "fetch statistics for a non existing projection" in {
      stats.statistics(project, CacheProjectionId("other")).accepted shouldEqual ProgressStatistics.empty.copy(
        totalEvents = 10,
        remainingEvents = 10,
        lastEventDateTime = Some(now)
      )
    }

    "fetch statistics for a non existing project" in {
      val nonExistingProject = ProjectRef.unsafe("a", "b")
      stats.statistics(nonExistingProject, CacheProjectionId("statistic")).accepted shouldEqual ProgressStatistics.empty
    }

    "fetch offset for a projection" in {
      val projectionId = CacheProjectionId("offset")
      val progress     = ProjectionProgress(Sequence(1), nowMinus5, 9, 1, 0, 1)
      progressesCache.put(projectionId, progress).accepted
      stats.offset(projectionId).accepted shouldEqual Sequence(1)
    }

    "fetch updated offset for a projection" in {
      val projectionId = CacheProjectionId("offset")
      val progress     = ProjectionProgress(Sequence(2), nowMinus5, 9, 1, 0, 1)
      progressesCache.put(projectionId, progress).accepted
      stats.offset(projectionId).accepted shouldEqual Sequence(2)
    }

    "fetch offset for a non existing projection" in {
      stats.offset(CacheProjectionId("not-exist")).accepted shouldEqual NoOffset
    }
  }

}
