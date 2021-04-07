package ch.epfl.bluebrain.nexus.delta.sdk

import akka.persistence.query.{NoOffset, Offset}
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.sdk.ProgressesStatistics.ProgressesCache
import ch.epfl.bluebrain.nexus.delta.sdk.cache.KeyValueStore
import ch.epfl.bluebrain.nexus.delta.sdk.model.ProgressStatistics
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectCountsCollection.ProjectCount
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.{ProjectionId, ProjectionProgress}
import com.typesafe.scalalogging.Logger
import monix.bio.UIO

/**
  * Retrieves the statistics for a specific projection progress compared to the baseline (the project counts).
  *
  * @param progressCache      a cache containing a collection of [[ProjectionProgress]], where the index key is the view projectionId
  * @param projectsCounts a cache containing the statistics (counts and latest consumed instant) for all the projects
  */
class ProgressesStatistics(progressCache: ProgressesCache, projectsCounts: ProjectsCounts) {

  private val logger: Logger = Logger[ProgressesStatistics.type]

  /**
    * Retrieves the progress of the passed ''project'' and compares them to the progress of the passed ''projection''
    * in order to compute the statistics
    */
  def statistics(project: ProjectRef, projectionId: ProjectionId): UIO[ProgressStatistics] =
    (progressCache.get(projectionId), projectsCounts.get(project)).mapN {
      case (Some(progress), Some(ProjectCount(projectCount, projectInstant))) =>
        ProgressStatistics(
          progress.processed,
          progress.discarded,
          progress.failed,
          projectCount,
          Some(projectInstant),
          Some(progress.timestamp)
        )

      case (Some(vProgress), None)                               =>
        logger.warn(
          s"Found progress for view '$projectionId' with value '$vProgress' but not found progress on project"
        )

        ProgressStatistics.empty
      case (_, Some(ProjectCount(projectCount, projectInstant))) =>
        ProgressStatistics(0, 0, 0, projectCount, Some(projectInstant), None)

      case _ => ProgressStatistics.empty
    }

  /**
    * Retrieves the progress for the passed ''project'' and returns the offset of its latest consumed item.
    * If the progress does not exist an empty Offset is returned
    * @return
    */
  def offset(projection: ProjectionId): UIO[Offset] =
    progressCache.get(projection).map(_.fold[Offset](NoOffset)(_.offset))
}

object ProgressesStatistics {
  type ProgressesCache = KeyValueStore[ProjectionId, ProjectionProgress[Unit]]
}
