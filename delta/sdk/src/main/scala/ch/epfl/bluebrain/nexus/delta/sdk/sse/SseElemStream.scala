package ch.epfl.bluebrain.nexus.delta.sdk.sse

import akka.http.scaladsl.model.sse.ServerSentEvent
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.RdfMarshalling.defaultPrinter
import ch.epfl.bluebrain.nexus.delta.sourcing.Transactors
import ch.epfl.bluebrain.nexus.delta.sourcing.config.QueryConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.query.{RefreshStrategy, SelectFilter, StreamingQuery}
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.{DroppedElem, FailedElem, SuccessElem}
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.{Elem, RemainingElems}
import io.circe.syntax.EncoderOps
import monix.bio.UIO

trait SseElemStream {

  /**
    * Allows to generate a non-terminating [[ServerSentEvent]] stream for the given project for the given tag
    * @param project
    *   the project to stream from
    * @param tag
    *   the tag to retain
    * @param start
    *   the offset to start with
    */
  def continuous(project: ProjectRef, selectFilter: SelectFilter, start: Offset): ServerSentEventStream

  /**
    * Allows to generate a [[ServerSentEvent]] stream for the given project for the given tag
    *
    * This stream terminates when reaching all elements have been returned
    *
    * @param project
    *   the project to stream from
    * @param tag
    *   the tag to retain
    * @param start
    *   the offset to start with
    */
  def currents(project: ProjectRef, selectFilter: SelectFilter, start: Offset): ServerSentEventStream

  /**
    * Get information about the remaining elements to stream
    * @param project
    *   the project to stream from
    * @param selectFilter
    *   what to filter for
    * @param start
    *   the offset to start with
    */
  def remaining(project: ProjectRef, selectFilter: SelectFilter, start: Offset): UIO[Option[RemainingElems]]
}

object SseElemStream {

  /**
    * Create a [[SseElemStream]]
    */
  def apply(qc: QueryConfig, xas: Transactors): SseElemStream = new SseElemStream {

    override def continuous(project: ProjectRef, selectFilter: SelectFilter, start: Offset): ServerSentEventStream =
      StreamingQuery.elems(project, start, selectFilter, qc, xas).map(toServerSentEvent)

    override def currents(project: ProjectRef, selectFilter: SelectFilter, start: Offset): ServerSentEventStream =
      StreamingQuery
        .elems(
          project,
          start,
          selectFilter,
          qc.copy(refreshStrategy = RefreshStrategy.Stop),
          xas
        )
        .map(toServerSentEvent)

    override def remaining(
        project: ProjectRef,
        selectFilter: SelectFilter,
        start: Offset
    ): UIO[Option[RemainingElems]] =
      StreamingQuery.remaining(project, selectFilter, start, xas)
  }

  private[sse] def toServerSentEvent(elem: Elem[Unit]): ServerSentEvent = {
    val tpe = elem match {
      case _: SuccessElem[Unit] => "Success"
      case _: DroppedElem       => "Dropped"
      case _: FailedElem        => "Failed"
    }
    ServerSentEvent(defaultPrinter.print(elem.asJson), tpe, elem.offset.value.toString)
  }
}
