package ch.epfl.bluebrain.nexus.delta.sdk

import akka.actor.typed.ActorSystem
import akka.persistence.query.{NoOffset, Offset}
import ch.epfl.bluebrain.nexus.delta.sdk.ProgressesStatistics.ProgressesCache
import ch.epfl.bluebrain.nexus.delta.sdk.cache.{KeyValueStore, KeyValueStoreConfig}
import ch.epfl.bluebrain.nexus.delta.sdk.model.ProgressStatistics
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectCountsCollection.ProjectCount
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.{ProjectionId, ProjectionProgress}
import com.typesafe.scalalogging.Logger
import monix.bio.UIO

/**
  * Retrieves the statistics for a specific projection progress compared to the baseline (the project counts).
  *
  * @param progressCache
  *   a cache containing a collection of [[ProjectionProgress]], where the index key is the view projectionId
  * @param projectsCounts
  *   a cache containing the statistics (counts and latest consumed instant) for all the projects
  */
class ProgressesStatistics(progressCache: ProgressesCache, projectsCounts: ProjectsCounts) {

  private val logger: Logger = Logger[ProgressesStatistics.type]

  /**
    * Retrieves the progress and project count for the provided ''project'' and ''projectionId'' and compute the
    * resulting statistics.
    *
    * @param project
    *   the project for which the counts are collected
    * @param projectionId
    *   the projection id for which the statistics are computed
    */
  def statistics(project: ProjectRef, projectionId: ProjectionId): UIO[ProgressStatistics] =
    projectsCounts.get(project).flatMap {
      case Some(count) => statistics(count, projectionId)
      case None        =>
        logger.warn(s"Project count not found for project '$project'")
        UIO.pure(ProgressStatistics.empty)
    }

  /**
    * Retrieves the progress of the provided ''projectionId'' and uses the provided ''count'' to compute its statistics.
    *
    * @param count
    *   a project count
    * @param projectionId
    *   the projection id for which the statistics are computed
    */
  def statistics(count: ProjectCount, projectionId: ProjectionId): UIO[ProgressStatistics] =
    progressCache.get(projectionId).map {
      case None           => ProgressStatistics(0, 0, 0, count.events, Some(count.lastProcessedEventDateTime), None)
      case Some(progress) =>
        ProgressStatistics(
          progress.processed,
          progress.discarded,
          progress.failed,
          count.events,
          Some(count.lastProcessedEventDateTime),
          Some(progress.timestamp)
        )
    }

  /**
    * Retrieves the progress for the passed ''project'' and returns the offset of its latest consumed item. If the
    * progress does not exist an empty Offset is returned
    * @return
    */
  def offset(projection: ProjectionId): UIO[Offset] =
    progressCache.get(projection).map(_.fold[Offset](NoOffset)(_.offset))
}

object ProgressesStatistics {
  type ProgressesCache = KeyValueStore[ProjectionId, ProjectionProgress[Unit]]

  /**
    * Creates a progress cache backed by Akka Distributed data with a default clock
    * @param id
    *   the identifier of the cache
    */
  def cache(id: String)(implicit as: ActorSystem[Nothing], config: KeyValueStoreConfig): ProgressesCache =
    KeyValueStore.distributedWithDefaultClock[ProjectionId, ProjectionProgress[Unit]](id)

}
