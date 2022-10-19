package ch.epfl.bluebrain.nexus.delta.sourcing.stream

import cats.effect.Clock
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.kernel.database.Transactors
import ch.epfl.bluebrain.nexus.delta.kernel.utils.IOUtils
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ThrowableUtils._
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sourcing.config.QueryConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EntityType, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.ProjectionStore.ProjectionProgressRow
import ch.epfl.bluebrain.nexus.delta.sourcing.implicits.IriInstances._
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.FailedElem
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.ProjectionStore.FailedElemLogRow
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.ProjectionStore.FailedElemLogRow.FailedElemData
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

  /**
    * Saves a list of failed elems
    *
    * @param metadata
    *   the metadata of the projection
    * @param failure
    *   the FailedElem to save
    */
  def saveFailedElems(metadata: ProjectionMetadata, failures: List[FailedElem]): UIO[Unit]

  /**
    * Saves one failed elem
    */
  protected def saveFailedElem(metadata: ProjectionMetadata, failure: FailedElem): ConnectionIO[Unit]

  /**
    * Get available failed elem entries for a given projection (provided by project and id), starting from a failed elem
    * offset.
    * @param projectionProject
    *   the project the projection belongs to
    * @param projectionId
    *   IRI of the projection
    * @param offset
    *   failed elem offset
    */
  def failedElemEntries(
      projectionProject: ProjectRef,
      projectionId: Iri,
      offset: Offset
  ): Stream[Task, FailedElemLogRow]

  /**
    * Get available failed elem entries for a given projection by projection name, starting from a failed elem offset.
    * @param projectionName
    *   the name of the projection
    * @param offset
    *   failed elem offset
    * @return
    */
  def failedElemEntries(
      projectionName: String,
      offset: Offset
  ): Stream[Task, FailedElemLogRow]

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

      override def failedElemEntries(
          projectionProject: ProjectRef,
          projectionId: Iri,
          offset: Offset
      ): Stream[Task, FailedElemLogRow] =
        sql"""SELECT * from public.failed_elem_logs
             |WHERE projection_project = $projectionProject
             |AND projection_id = $projectionId
             |AND ordering >= $offset
             |ORDER BY ordering DESC""".stripMargin
          .query[FailedElemLogRow]
          .streamWithChunkSize(config.batchSize)
          .transact(xas.streaming)

      override def failedElemEntries(
          projectionName: String,
          offset: Offset
      ): Stream[Task, FailedElemLogRow] =
        sql"""SELECT * from public.failed_elem_logs
             |WHERE projection_name = $projectionName
             |AND ordering >= $offset
             |ORDER BY ordering DESC""".stripMargin
          .query[FailedElemLogRow]
          .streamWithChunkSize(config.batchSize)
          .transact(xas.streaming)

      override def saveFailedElems(
          metadata: ProjectionMetadata,
          failures: List[FailedElem]
      ): UIO[Unit] =
        failures
          .traverse(elem => saveFailedElem(metadata, elem))
          .transact(xas.write)
          .void
          .hideErrors

      override protected def saveFailedElem(
          metadata: ProjectionMetadata,
          failure: FailedElem
      ): ConnectionIO[Unit] =
        sql"""
             | INSERT INTO public.failed_elem_logs (
             |  projection_name,
             |  projection_module,
             |  projection_project,
             |  projection_id,
             |  entity_type,
             |  elem_offset,
             |  elem_id,
             |  error_type,
             |  message,
             |  stack_trace
             | )
             | VALUES (
             |  ${metadata.name},
             |  ${metadata.module},
             |  ${metadata.project},
             |  ${metadata.resourceId},
             |  ${failure.tpe},
             |  ${failure.offset},
             |  ${failure.id},
             |  ${failure.throwable.getClass.getCanonicalName},
             |  ${failure.throwable.getMessage},
             |  ${stackTraceAsString(failure.throwable)}
             | )""".stripMargin.update.run.void
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

  /**
    * The row of the failed_elem_log table
    */
  final case class FailedElemLogRow(
      ordering: Offset,
      projectionMetadata: ProjectionMetadata,
      failedElemData: FailedElemData,
      instant: Instant
  )

  object FailedElemLogRow {
    private type Row =
      (
          Offset,
          String,
          String,
          Option[ProjectRef],
          Option[Iri],
          EntityType,
          Offset,
          String,
          String,
          String,
          String,
          Instant
      )

    /**
      * Helper case class to structure FailedElemLogRow
      */
    case class FailedElemData(
        id: String,
        entityType: EntityType,
        offset: Offset,
        errorType: String,
        message: String,
        stackTrace: String
    )

    implicit val failedElemLogRow: Read[FailedElemLogRow] = {
      Read[Row].map {
        case (
              ordering,
              name,
              module,
              project,
              resourceId,
              entityType,
              elemOffset,
              elemId,
              errorType,
              message,
              stackTrace,
              instant
            ) =>
          FailedElemLogRow(
            ordering,
            ProjectionMetadata(module, name, project, resourceId),
            FailedElemData(elemId, entityType, elemOffset, errorType, message, stackTrace),
            instant
          )
      }
    }
  }

}
