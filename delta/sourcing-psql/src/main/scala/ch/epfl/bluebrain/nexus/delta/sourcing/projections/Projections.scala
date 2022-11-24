package ch.epfl.bluebrain.nexus.delta.sourcing.projections

import akka.http.scaladsl.model.sse.ServerSentEvent
import cats.syntax.all._
import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.kernel.database.Transactors
import ch.epfl.bluebrain.nexus.delta.kernel.utils.IOUtils
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.{JsonLdApi, JsonLdJavaApi}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.sourcing.ProgressStatistics
import ch.epfl.bluebrain.nexus.delta.sourcing.config.QueryConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{ElemStream, ProjectRef, Tag}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.model.ProjectionRestart
import ch.epfl.bluebrain.nexus.delta.sourcing.query.StreamingQuery
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.FailedElem
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.ProjectionStore.FailedElemLogRow
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.{ProjectionMetadata, ProjectionProgress, ProjectionStore, RemainingElems}
import fs2.Stream
import io.circe.Printer
import monix.bio.{Task, UIO}

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
  def failedElemEntries(projectionName: String, offset: Offset): Stream[Task, FailedElemLogRow]

  /**
    * Get available failed elem entries for a given projection (provided by project and id), starting from a failed elem
    * offset as a stream of Server Sent Events
    * @param projectionProject
    *   the project the projection belongs to
    * @param projectionId
    *   IRI of the projection
    * @param offset
    *   failed elem offset
    */
  def failedElemSses(projectionProject: ProjectRef, projectionId: Iri, offset: Offset)(implicit
      rcr: RemoteContextResolution
  ): Stream[Task, ServerSentEvent]

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
  def statistics(project: ProjectRef, tag: Option[Tag], projectionId: String): UIO[ProgressStatistics]

  /**
    * Retrieves the progress of the provided ''projectionId'' and uses the provided ''remaining elems'' to compute its
    * statistics.
    *
    * @param projectionId
    *   the projection id for which the statistics are computed
    * @param remaining
    *   a description of the remaining elements to stream
    */
  def statistics(projectionId: String, remaining: Option[RemainingElems]): UIO[ProgressStatistics]
}

object Projections {

  def apply(xas: Transactors, config: QueryConfig, restartTtl: FiniteDuration)(implicit
      clock: Clock[UIO]
  ): Projections =
    new Projections {
      private val projectionStore        = ProjectionStore(xas, config)
      private val projectionRestartStore = new ProjectionRestartStore(xas, config)

      implicit private val api: JsonLdApi = JsonLdJavaApi.lenient
      private val defaultPrinter: Printer = Printer(dropNullValues = true, indent = "")

      override def progress(name: String): UIO[Option[ProjectionProgress]] = projectionStore.offset(name)

      override def save(metadata: ProjectionMetadata, progress: ProjectionProgress): UIO[Unit] =
        projectionStore.save(metadata, progress)

      override def delete(name: String): UIO[Unit] = projectionStore.delete(name)

      override def saveFailedElems(metadata: ProjectionMetadata, failures: List[FailedElem]): UIO[Unit] =
        projectionStore.saveFailedElems(metadata, failures)

      override def failedElemEntries(
          projectionProject: ProjectRef,
          projectionId: Iri,
          offset: Offset
      ): Stream[Task, FailedElemLogRow] =
        projectionStore.failedElemEntries(projectionProject, projectionId, offset)

      override def failedElemEntries(projectionName: String, offset: Offset): Stream[Task, FailedElemLogRow] =
        projectionStore.failedElemEntries(projectionName, offset)

      override def failedElemSses(projectionProject: ProjectRef, projectionId: Iri, offset: Offset)(implicit
          rcr: RemoteContextResolution
      ): Stream[Task, ServerSentEvent] =
        failedElemEntries(projectionProject, projectionId, offset).evalMap { felem =>
          felem.failedElemData.toCompactedJsonLd.map { compactJson =>
            ServerSentEvent(
              defaultPrinter.print(compactJson.json),
              "IndexingFailure",
              felem.ordering.value.toString
            )
          }
        }

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

      def statistics(project: ProjectRef, tag: Option[Tag], projectionId: String): UIO[ProgressStatistics] =
        for {
          current   <- progress(projectionId)
          remaining <- current.flatTraverse { c =>
            StreamingQuery.remaining(project, tag.getOrElse(Tag.latest), c.offset, xas)
          }
        } yield statistics(current, remaining)

      def statistics(projectionId: String, remaining: Option[RemainingElems]): UIO[ProgressStatistics] =
        progress(projectionId).map(statistics(_, remaining))

      private def statistics(current: Option[ProjectionProgress], remaining: Option[RemainingElems]) =
        (current, remaining) match {
          case (Some(c), Some (r)) => ProgressStatistics (
                c.processed,
              c.discarded,
              c.failed,
              r.count,
              Some (r.maxInstant),
            Some (c.instant)
            )
          case (None, Some (r)) => ProgressStatistics (
            0L,
            0L,
            0L,
            r.count,
            Some (r.maxInstant),
            None
          )
          case (Some(c), None) => ProgressStatistics (
            c.processed,
            c.discarded,
            c.failed,
            c.processed,
            Some (c.instant),
            Some (c.instant)
          )
          case (None, None) => ProgressStatistics.empty
        }
    }
}
