package ch.epfl.bluebrain.nexus.delta.sourcing.stream

import cats.effect.{Clock, IO}
import ch.epfl.bluebrain.nexus.delta.kernel.utils.IOInstant
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sourcing.Transactors
import ch.epfl.bluebrain.nexus.delta.sourcing.config.QueryConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.implicits._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.ProjectionStore.ProjectionProgressRow
import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import fs2.Stream

import java.time.Instant

/**
  * Persistent operations for projections.
  */
trait ProjectionStore {

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
    * @param name
    *   the name of the projection to reset
    */
  def reset(name: String): IO[Unit]

  /**
    * Retrieves a projection offset if found.
    *
    * @param name
    *   the name of the projection
    */
  def offset(name: String): IO[Option[ProjectionProgress]]

  /**
    * Deletes a projection offset if found.
    *
    * @param name
    *   the name of the projection
    */
  def delete(name: String): IO[Unit]

  /**
    * @return
    *   all known projection offset entries
    */
  def entries: Stream[IO, ProjectionProgressRow]

}

object ProjectionStore {

  def apply(xas: Transactors, config: QueryConfig)(implicit clock: Clock[IO]): ProjectionStore =
    new ProjectionStore {
      override def save(
          metadata: ProjectionMetadata,
          progress: ProjectionProgress
      ): IO[Unit] =
        IOInstant.now.flatMap { instant =>
          sql"""INSERT INTO projection_offsets (name, module, project, resource_id, ordering, processed, discarded, failed, created_at, updated_at)
               |VALUES (${metadata.name}, ${metadata.module} ,${metadata.project}, ${metadata.resourceId}, ${progress.offset.value}, ${progress.processed}, ${progress.discarded}, ${progress.failed}, $instant, $instant)
               |ON CONFLICT (name)
               |DO UPDATE set
               |  module = EXCLUDED.module,
               |  project = EXCLUDED.project,
               |  resource_id = EXCLUDED.resource_id,
               |  ordering = EXCLUDED.ordering,
               |  processed = EXCLUDED.processed,
               |  discarded = EXCLUDED.discarded,
               |  failed = EXCLUDED.failed,
               |  updated_at = EXCLUDED.updated_at;
               |""".stripMargin.update.run
            .transact(xas.writeCE)
            .void
        }

      override def reset(name: String): IO[Unit] =
        IOInstant.now.flatMap { instant =>
          sql"""UPDATE projection_offsets
                SET ordering   = 0,
                    processed  = 0,
                    discarded  = 0,
                    failed     = 0,
                    created_at = $instant,
                    updated_at = $instant
                WHERE name = $name
             """.stripMargin.update.run
            .transact(xas.writeCE)
            .void
        }

      override def offset(name: String): IO[Option[ProjectionProgress]] =
        sql"""SELECT * FROM projection_offsets
             |WHERE name = $name;
             |""".stripMargin
          .query[ProjectionProgressRow]
          .map(_.progress)
          .option
          .transact(xas.readCE)

      override def delete(name: String): IO[Unit] =
        sql"""DELETE FROM projection_offsets
             |WHERE name = $name;
             |""".stripMargin.update.run
          .transact(xas.writeCE)
          .void

      override def entries: Stream[IO, ProjectionProgressRow] =
        sql"""SELECT * from projection_offsets;"""
          .query[ProjectionProgressRow]
          .streamWithChunkSize(config.batchSize)
          .transact(xas.readCE)
    }

  final case class ProjectionProgressRow(
      name: String,
      module: String,
      project: Option[ProjectRef],
      resourceId: Option[Iri],
      progress: ProjectionProgress,
      createdAt: Instant,
      updatedAt: Instant
  )

  object ProjectionProgressRow {
    implicit val projectionProgressRowRead: Read[ProjectionProgressRow] = {
      Read[(String, String, Option[ProjectRef], Option[Iri], Long, Long, Long, Long, Instant, Instant)].map {
        case (name, module, project, resourceId, offset, processed, discarded, failed, createdAt, updatedAt) =>
          ProjectionProgressRow(
            name,
            module,
            project,
            resourceId,
            ProjectionProgress(Offset.from(offset), updatedAt, processed, discarded, failed),
            createdAt,
            updatedAt
          )
      }
    }
  }

}
