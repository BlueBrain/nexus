package ch.epfl.bluebrain.nexus.delta.sourcing.stream

import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.kernel.database.Transactors
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sourcing.config.QueryConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.FailedElem
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.ElemErrorStore.ElemErrorRow
import ch.epfl.bluebrain.nexus.delta.sourcing.implicits.IriInstances._
import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import fs2.Stream
import monix.bio.Task

import java.time.Instant

trait ElemErrorStore {

  /**
    * Save one error
    */
  def save(metadata: ProjectionMetadata, failure: FailedElem): ConnectionIO[Unit]

  /**
    * Get all errors for a given projection id
    */
  def entries(
      projectionProject: ProjectRef,
      projectionId: String,
      offset: Offset
  ): Stream[Task, ElemErrorRow]

  /**
    * Get all errors start from the given offset
    */
  def entries(projectionName: String, offset: Offset): Stream[Task, ElemErrorRow]

}

object ElemErrorStore {

  def apply(xas: Transactors, config: QueryConfig): ElemErrorStore =
    new ElemErrorStore {

      override def entries(
          projectionProject: ProjectRef,
          projectionId: String,
          offset: Offset
      ): Stream[Task, ElemErrorRow] =
        sql"""SELECT * from public.elem_errors
             |WHERE projection_id = $projectionId
             |AND ordering >= ${offset.value}
             |ORDER BY ordering DESC""".stripMargin
          .query[ElemErrorRow]
          .streamWithChunkSize(config.batchSize)
          .transact(xas.streaming)

      override def entries(
          projectionName: String,
          offset: Offset
      ): Stream[Task, ElemErrorRow] =
        sql"""SELECT * from public.elem_errors
             |WHERE projection_name = $projectionName
             |AND ordering >= ${offset.value}
             |ORDER BY ordering DESC""".stripMargin
          .query[ElemErrorRow]
          .streamWithChunkSize(config.batchSize)
          .transact(xas.streaming)

      override def save(
          metadata: ProjectionMetadata,
          failure: FailedElem
      ): ConnectionIO[Unit] =
        sql"""
               | INSERT INTO public.elem_errors (
               |  projection_name,
               |  projection_module,
               |  projection_project,
               |  projection_id,
               |  entity_type,
               |  elem_offset,
               |  elem_id,
               |  message,
               |  stack_trace
               | )
               | VALUES (
               |  ${metadata.name},
               |  ${metadata.module},
               |  ${metadata.project},
               |  ${metadata.resourceId},
               |  ${failure.tpe.value},
               |  ${failure.offset.value},
               |  ${failure.id},
               |  ${failure.throwable.getMessage},
               |  ${failure.throwable.getStackTrace.map(_.toString).toString}
               | )""".stripMargin.update.run.void
    }

  final case class ElemErrorRow(
      projectionName: String,
      projectionModule: String,
      projectionProject: Option[ProjectRef],
      projectionId: Option[Iri],
      entityType: String,
      elemOffset: Long,
      elemId: String,
      message: String,
      stackTrace: String,
      instant: Instant
  )

  object ElemErrorRow {
    implicit val projectionErrorRow: Read[ElemErrorRow] = {
      Read[(Long, String, String, Option[ProjectRef], Option[Iri], String, Long, String, String, String, Instant)].map {
        case (_, name, module, project, resourceId, entityType, elemOffset, elemId, message, stackTrace, instant) =>
          ElemErrorRow(
            name,
            module,
            project,
            resourceId,
            entityType,
            elemOffset,
            elemId,
            message,
            stackTrace,
            instant
          )
      }
    }

  }

}
