package ch.epfl.bluebrain.nexus.delta.sourcing.projections

import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.kernel.utils.IOUtils
import ch.epfl.bluebrain.nexus.delta.sourcing.config.QueryConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{ElemStream, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.model.ProjectionRestart
import ch.epfl.bluebrain.nexus.delta.sourcing.query.{SelectFilter, StreamingQuery}
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.{ProjectionMetadata, ProjectionProgress, ProjectionStore}
import ch.epfl.bluebrain.nexus.delta.sourcing.{ProgressStatistics, Transactors}
import monix.bio.UIO

import scala.concurrent.duration.FiniteDuration

trait Projections {

  /**
    * Retrieves a projection progress if found.
    *
    * @param name
    *   the name of the projection
    */
  def progress(name: String): UIO[Option[ProjectionProgress]]

  /**
    * Retrieves the offset for a given projection.
    *
    * @param name
    *   the name of the projection
    */
  def offset(name: String): UIO[Offset] = progress(name).map(_.fold[Offset](Offset.start)(_.offset))

  /**
    * Saves a projection offset.
    *
    * @param metadata
    *   the metadata of the projection
    * @param progress
    *   the offset to save
    */
  def save(metadata: ProjectionMetadata, progress: ProjectionProgress): UIO[Unit]

  /**
    * Deletes a projection offset if found.
    *
    * @param name
    *   the name of the projection
    */
  def delete(name: String): UIO[Unit]

  /**
    * Schedules a restart for the given projection at the given offset
    * @param projectionName
    *   the name of the projection
    */
  def scheduleRestart(projectionName: String)(implicit subject: Subject): UIO[Unit]

  /**
    * Get scheduled projection restarts from a given offset
    * @param offset
    *   the offset to start from
    */
  def restarts(offset: Offset): ElemStream[ProjectionRestart]

  /**
    * Acknowledge that a restart has been performed
    * @param id
    *   the identifier of the restart
    * @return
    */
  def acknowledgeRestart(id: Offset): UIO[Unit]

  /**
    * Deletes projection restarts older than the configured period
    */
  def deleteExpiredRestarts(): UIO[Unit]

  /**
    * Returns the statistics for the given projection in the given project
    *
    * @param project
    *   the project for which the counts are collected
    * @param tag
    *   the tag the projection is working on
    * @param projectionId
    *   the projection id for which the statistics are computed
    */
  def statistics(project: ProjectRef, selectFilter: SelectFilter, projectionId: String): UIO[ProgressStatistics]
}

object Projections {

  def apply(xas: Transactors, config: QueryConfig, restartTtl: FiniteDuration)(implicit
      clock: Clock[UIO]
  ): Projections =
    new Projections {
      private val projectionStore        = ProjectionStore(xas, config)
      private val projectionRestartStore = new ProjectionRestartStore(xas, config)

      override def progress(name: String): UIO[Option[ProjectionProgress]] = projectionStore.offset(name)

      override def save(metadata: ProjectionMetadata, progress: ProjectionProgress): UIO[Unit] =
        projectionStore.save(metadata, progress)

      override def delete(name: String): UIO[Unit] = projectionStore.delete(name)

      override def scheduleRestart(projectionName: String)(implicit subject: Subject): UIO[Unit] = {
        IOUtils.instant.flatMap { now =>
          projectionRestartStore.save(ProjectionRestart(projectionName, now, subject))
        }
      }

      override def restarts(offset: Offset): ElemStream[ProjectionRestart] = projectionRestartStore.stream(offset)

      override def acknowledgeRestart(id: Offset): UIO[Unit] = projectionRestartStore.acknowledge(id)

      override def deleteExpiredRestarts(): UIO[Unit] =
        IOUtils.instant.flatMap { now =>
          projectionRestartStore.deleteExpired(now.minusMillis(restartTtl.toMillis))
        }

      override def statistics(
          project: ProjectRef,
          selectFilter: SelectFilter,
          projectionId: String
      ): UIO[ProgressStatistics] =
        for {
          current   <- progress(projectionId)
          remaining <-
            StreamingQuery.remaining(project, selectFilter, current.fold(Offset.start)(_.offset), xas)
        } yield ProgressStatistics(current, remaining)
    }
}
