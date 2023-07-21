package ch.epfl.bluebrain.nexus.delta.sourcing.projections

import cats.effect.Clock
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.kernel.utils.IOUtils
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ThrowableUtils._
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sourcing.Transactors
import ch.epfl.bluebrain.nexus.delta.sourcing.config.QueryConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.implicits._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.FailedElem
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.ProjectionStore.FailedElemLogRow
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.{ProjectionMetadata, ProjectionStore}
import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import fs2.Stream
import monix.bio.{Task, UIO}

import java.time.Instant

/**
  * Persistent operations for errors raised by projections
  */
trait FailedElemLogStore {

  /**
    * Saves a list of failed elems
    *
    * @param metadata
    *   the metadata of the projection
    * @param failures
    *   the FailedElem to save
    */
  def saveFailedElems(metadata: ProjectionMetadata, failures: List[FailedElem]): UIO[Unit]

  /**
    * Saves one failed elem
    */
  protected def saveFailedElem(metadata: ProjectionMetadata, failure: FailedElem, instant: Instant): ConnectionIO[Unit]

  /**
    * Get available failed elem entries for a given projection (provided by project and id), starting from a failed elem
    * offset.
    *
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
    *
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

object FailedElemLogStore {

  private val logger: Logger = Logger[ProjectionStore]

  def apply(xas: Transactors, config: QueryConfig)(implicit clock: Clock[UIO]): FailedElemLogStore =
    new FailedElemLogStore {

      override def saveFailedElems(metadata: ProjectionMetadata, failures: List[FailedElem]): UIO[Unit] = {
        val log  = logger.debug(s"[${metadata.name}] Saving ${failures.length} failed elems.")
        val save = IOUtils.instant.flatMap { instant =>
          failures.traverse(elem => saveFailedElem(metadata, elem, instant)).transact(xas.write).void.hideErrors
        }
        log >> save
      }

      override protected def saveFailedElem(
          metadata: ProjectionMetadata,
          failure: FailedElem,
          instant: Instant
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
           |  elem_project,
           |  rev,
           |  error_type,
           |  message,
           |  stack_trace,
           |  instant
           | )
           | VALUES (
           |  ${metadata.name},
           |  ${metadata.module},
           |  ${metadata.project},
           |  ${metadata.resourceId},
           |  ${failure.tpe},
           |  ${failure.offset},
           |  ${failure.id},
           |  ${failure.project},
           |  ${failure.rev},
           |  ${failure.throwable.getClass.getCanonicalName},
           |  ${failure.throwable.getMessage},
           |  ${stackTraceAsString(failure.throwable)},
           |  $instant
           | )""".stripMargin.update.run.void

      override def failedElemEntries(
          projectionProject: ProjectRef,
          projectionId: Iri,
          offset: Offset
      ): Stream[Task, FailedElemLogRow] =
        sql"""SELECT * from public.failed_elem_logs
           |WHERE projection_project = $projectionProject
           |AND projection_id = $projectionId
           |AND ordering > $offset
           |ORDER BY ordering ASC""".stripMargin
          .query[FailedElemLogRow]
          .streamWithChunkSize(config.batchSize)
          .transact(xas.read)

      override def failedElemEntries(projectionName: String, offset: Offset): Stream[Task, FailedElemLogRow] =
        sql"""SELECT * from public.failed_elem_logs
           |WHERE projection_name = $projectionName
           |AND ordering > $offset
           |ORDER BY ordering ASC""".stripMargin
          .query[FailedElemLogRow]
          .streamWithChunkSize(config.batchSize)
          .transact(xas.read)
    }

}
