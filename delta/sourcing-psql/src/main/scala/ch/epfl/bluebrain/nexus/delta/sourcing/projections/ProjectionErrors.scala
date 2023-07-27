package ch.epfl.bluebrain.nexus.delta.sourcing.projections

import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.kernel.search.Pagination.FromPagination
import ch.epfl.bluebrain.nexus.delta.kernel.search.TimeRange
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sourcing.Transactors
import ch.epfl.bluebrain.nexus.delta.sourcing.config.QueryConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{FailedElemLogRow, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.FailedElem
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.ProjectionMetadata
import fs2.Stream
import monix.bio.{Task, UIO}

trait ProjectionErrors {

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
  def failedElemEntries(projectionName: String, offset: Offset): Stream[Task, FailedElemLogRow]

  /**
    * Return the total of errors for the given projection on a time window ordered by instant
    *
    * @param project
    *   the project of the projection
    * @param projectionId
    *   its identifier
    * @param timeRange
    *   the time range to restrict on
    */
  def count(project: ProjectRef, projectionId: Iri, timeRange: TimeRange): UIO[Long]

  /**
    * Return a list of errors for the given projection on a time window ordered by instant
    *
    * @param project
    *   the project of the projection
    * @param projectionId
    *   its identifier
    * @param pagination
    *   the pagination to apply
    * @param timeRange
    *   the time range to restrict on
    */
  def list(
      project: ProjectRef,
      projectionId: Iri,
      pagination: FromPagination,
      timeRange: TimeRange
  ): UIO[List[FailedElemLogRow]]

}

object ProjectionErrors {

  def apply(xas: Transactors, config: QueryConfig)(implicit clock: Clock[UIO]): ProjectionErrors =
    new ProjectionErrors {

      private val store = FailedElemLogStore(xas, config)

      override def saveFailedElems(metadata: ProjectionMetadata, failures: List[FailedElem]): UIO[Unit] =
        store.save(metadata, failures)

      override def failedElemEntries(
          projectionProject: ProjectRef,
          projectionId: Iri,
          offset: Offset
      ): Stream[Task, FailedElemLogRow] = store.stream(projectionProject, projectionId, offset)

      override def failedElemEntries(projectionName: String, offset: Offset): Stream[Task, FailedElemLogRow] =
        store.stream(projectionName, offset)

      override def count(project: ProjectRef, projectionId: Iri, timeRange: TimeRange): UIO[Long] =
        store.count(project, projectionId, timeRange)

      override def list(
          project: ProjectRef,
          projectionId: Iri,
          pagination: FromPagination,
          timeRange: TimeRange
      ): UIO[List[FailedElemLogRow]] = store.list(project, projectionId, pagination, timeRange)
    }

}
