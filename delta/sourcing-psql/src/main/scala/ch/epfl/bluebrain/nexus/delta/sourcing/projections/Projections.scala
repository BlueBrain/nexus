package ch.epfl.bluebrain.nexus.delta.sourcing.projections

import cats.effect.{Clock, IO}
import ch.epfl.bluebrain.nexus.delta.sourcing.config.{PurgeConfig, QueryConfig}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.model.ProjectionRestart
import ch.epfl.bluebrain.nexus.delta.sourcing.query.{SelectFilter, StreamingQuery}
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.PurgeProjectionCoordinator.PurgeProjection
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.{ProjectionMetadata, ProjectionProgress, ProjectionStore}
import ch.epfl.bluebrain.nexus.delta.sourcing.{ProgressStatistics, Transactors}
import fs2.Stream

import java.time.Instant

trait Projections {

  /**
    * Retrieves a projection progress if found.
    *
    * @param name
    *   the name of the projection
    */
  def progress(name: String): IO[Option[ProjectionProgress]]

  /**
    * Retrieves the offset for a given projection.
    *
    * @param name
    *   the name of the projection
    */
  def offset(name: String): IO[Offset] = progress(name).map(_.fold[Offset](Offset.start)(_.offset))

  /**
    * Saves a projection offset.
    *
    * @param metadata
    *   the metadata of the projection
    * @param progress
    *   the offset to save
    */
  def save(metadata: ProjectionMetadata, progress: ProjectionProgress): IO[Unit]

  /**
    * Resets the progress of a projection to 0, and the instants (createdAt, updatedAt) to the time of the reset
    *
    * @param name
    *   the name of the projection to reset
    */
  def reset(name: String): IO[Unit]

  /**
    * Deletes a projection offset if found.
    *
    * @param name
    *   the name of the projection
    */
  def delete(name: String): IO[Unit]

  /**
    * Schedules a restart for the given projection at the given offset
    * @param projectionName
    *   the name of the projection
    */
  def scheduleRestart(projectionName: String)(implicit subject: Subject): IO[Unit]

  /**
    * Get scheduled projection restarts from a given offset
    * @param offset
    *   the offset to start from
    */
  def restarts(offset: Offset): Stream[IO, (Offset, ProjectionRestart)]

  /**
    * Acknowledge that a restart has been performed
    * @param id
    *   the identifier of the restart
    * @return
    */
  def acknowledgeRestart(id: Offset): IO[Unit]

  /**
    * Deletes projection restarts older than the configured period
    */
  def deleteExpiredRestarts(instant: Instant): IO[Unit]

  /**
    * Returns the statistics for the given projection in the given project
    *
    * @param project
    *   the project for which the counts are collected
    * @param selectFilter
    *   what to filter for
    * @param projectionId
    *   the projection id for which the statistics are computed
    */
  def statistics(project: ProjectRef, selectFilter: SelectFilter, projectionId: String): IO[ProgressStatistics]
}

object Projections {

  def apply(xas: Transactors, config: QueryConfig, clock: Clock[IO]): Projections =
    new Projections {
      private val projectionStore        = ProjectionStore(xas, config, clock)
      private val projectionRestartStore = new ProjectionRestartStore(xas, config)

      override def progress(name: String): IO[Option[ProjectionProgress]] = projectionStore.offset(name)

      override def save(metadata: ProjectionMetadata, progress: ProjectionProgress): IO[Unit] =
        projectionStore.save(metadata, progress)

      override def reset(name: String): IO[Unit] = projectionStore.reset(name)

      override def delete(name: String): IO[Unit] = projectionStore.delete(name)

      override def scheduleRestart(projectionName: String)(implicit subject: Subject): IO[Unit] = {
        clock.realTimeInstant.flatMap { now =>
          projectionRestartStore.save(ProjectionRestart(projectionName, now, subject))
        }
      }

      override def restarts(offset: Offset): Stream[IO, (Offset, ProjectionRestart)] =
        projectionRestartStore.stream(offset)

      override def acknowledgeRestart(id: Offset): IO[Unit] = projectionRestartStore.acknowledge(id)

      override def deleteExpiredRestarts(instant: Instant): IO[Unit] = projectionRestartStore.deleteExpired(instant)

      override def statistics(
          project: ProjectRef,
          selectFilter: SelectFilter,
          projectionId: String
      ): IO[ProgressStatistics] =
        for {
          current   <- progress(projectionId)
          remaining <-
            StreamingQuery.remaining(project, selectFilter, current.fold(Offset.start)(_.offset), xas)
        } yield ProgressStatistics(current, remaining)
    }

  val purgeRestartMetadata                                                                 = ProjectionMetadata("system", "purge-projection-restarts", None, None)
  def purgeExpiredRestarts(projections: Projections, config: PurgeConfig): PurgeProjection =
    PurgeProjection(purgeRestartMetadata, config, projections.deleteExpiredRestarts)
}
