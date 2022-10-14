package ch.epfl.bluebrain.nexus.delta.sourcing.stream

import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.kernel.database.Transactors
import ch.epfl.bluebrain.nexus.delta.kernel.utils.IOUtils
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sourcing.config.QueryConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.ProjectionStore.ProjectionProgressRow
import ch.epfl.bluebrain.nexus.delta.sourcing.implicits.IriInstances._
import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import fs2.Stream
import monix.bio.{Task, UIO}

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
  def save(metadata: ProjectionMetadata, progress: ProjectionProgress): UIO[Unit]

  /**
    * Retrieves a projection offset if found.
    *
    * @param name
    *   the name of the projection
    */
  def offset(name: String): UIO[Option[ProjectionProgress]]

  /**
    * Deletes a projection offset if found.
    *
    * @param name
    *   the name of the projection
    */
  def delete(name: String): UIO[Unit]

  /**
    * @return
    *   all known projection offset entries
    */
  def entries: Stream[Task, ProjectionProgressRow]

}

object ProjectionStore {

  def apply(xas: Transactors, config: QueryConfig)(implicit clock: Clock[UIO]): ProjectionStore =
    new ProjectionStore {
      override def save(
          metadata: ProjectionMetadata,
          progress: ProjectionProgress
      ): UIO[Unit] =
        IOUtils.instant.flatMap { instant =>
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
            .transact(xas.streaming)
            .void
            .hideErrors
        }

      override def offset(name: String): UIO[Option[ProjectionProgress]] =
        sql"""SELECT * FROM projection_offsets
             |WHERE name = $name;
             |""".stripMargin
          .query[ProjectionProgressRow]
          .map(_.progress)
          .option
          .transact(xas.streaming)
          .hideErrors

      override def delete(name: String): UIO[Unit] =
        sql"""DELETE FROM projection_offsets
             |WHERE name = $name;
             |""".stripMargin.update.run
          .transact(xas.streaming)
          .void
          .hideErrors

      override def entries: Stream[Task, ProjectionProgressRow] =
        sql"""SELECT * from projection_offsets;"""
          .query[ProjectionProgressRow]
          .streamWithChunkSize(config.batchSize)
          .transact(xas.streaming)
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
