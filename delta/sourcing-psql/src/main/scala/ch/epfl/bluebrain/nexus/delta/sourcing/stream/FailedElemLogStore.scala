package ch.epfl.bluebrain.nexus.delta.sourcing.stream

import ch.epfl.bluebrain.nexus.delta.kernel.database.Transactors
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sourcing.config.QueryConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EntityType, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.FailedElem
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.FailedElemLogStore.FailedElemLogRow
import ch.epfl.bluebrain.nexus.delta.sourcing.implicits.IriInstances._
import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import fs2.Stream
import monix.bio.{Task, UIO}

import java.io.{PrintWriter, StringWriter}
import java.time.Instant

trait FailedElemLogStore {

  /**
    * Save one error
    */
  def save(metadata: ProjectionMetadata, failure: FailedElem): UIO[Unit]

  /**
    * Get all errors for a given projection id
    */
  def entries(
      projectionProject: ProjectRef,
      projectionId: Iri,
      offset: Offset
  ): Stream[Task, FailedElemLogRow]

  /**
    * Get all errors start from the given offset
    */
  def entries(projectionName: String, offset: Offset): Stream[Task, FailedElemLogRow]

}

object FailedElemLogStore {

  def apply(xas: Transactors, config: QueryConfig): FailedElemLogStore =
    new FailedElemLogStore {

      override def entries(
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

      override def entries(
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

      override def save(
          metadata: ProjectionMetadata,
          failure: FailedElem
      ): UIO[Unit] =
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
               | )""".stripMargin.update.run
          .transact(xas.write)
          .void
          .hideErrors
    }

  /**
    * Outputs readable stack trace for the given throwable.
    */
  def stackTraceAsString(t: Throwable): String = {
    val sw = new StringWriter
    t.printStackTrace(new PrintWriter(sw))
    sw.toString
  }

  /**
    * Helper case class to structure FailedElemLogRow
    */
  final protected case class FailedElemData(
      id: String,
      entityType: EntityType,
      offset: Offset,
      errorType: String,
      message: String,
      stackTrace: String
  )

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
    implicit val failedElemLogRow: Read[FailedElemLogRow] = {
      Read[
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
      ].map {
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
