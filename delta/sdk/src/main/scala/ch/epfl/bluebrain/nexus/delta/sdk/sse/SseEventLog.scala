package ch.epfl.bluebrain.nexus.delta.sdk.sse

import akka.http.scaladsl.model.sse.ServerSentEvent
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.error.ServiceError.UnknownSseLabel
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.RdfMarshalling.defaultPrinter
import ch.epfl.bluebrain.nexus.delta.sdk.sse.SseEncoder.SseData
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sourcing.event.EventStreaming
import ch.epfl.bluebrain.nexus.delta.sourcing.model._
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset.{At, Start}
import ch.epfl.bluebrain.nexus.delta.sourcing.{MultiDecoder, Scope, Transactors}
import fs2.Stream
import io.circe.syntax.EncoderOps

/**
  * An event log that reads events from a [[Stream]] and transforms each event to JSON in preparation for consumption by
  * SSE routes
  */
trait SseEventLog {

  /**
    * Get stream of server sent events for the given selector
    *
    * @param selector
    *   to stream only events from a subset of the entity types
    *
    * @param offset
    *   the offset to start from
    */
  def streamBy(selector: Label, offset: Offset): ServerSentEventStream

  /**
    * Get stream of server sent events inside an organization
    *
    * @param org
    *   the organization label
    * @param offset
    *   the offset to start from
    */
  def stream(
      org: Label,
      offset: Offset
  ): IO[ServerSentEventStream]

  /**
    * Get stream of server sent events inside an organization
    *
    * @param selector
    *   to stream only events from a subset of the entity types
    *
    * @param org
    *   the organization label
    * @param offset
    *   the offset to start from
    */
  def streamBy(selector: Label, org: Label, offset: Offset): IO[ServerSentEventStream]

  /**
    * Get stream of server sent events inside an project
    *
    * @param project
    *   the project reference
    * @param offset
    *   the offset to start from
    */
  def stream(
      project: ProjectRef,
      offset: Offset
  ): IO[ServerSentEventStream]

  /**
    * Get stream of server sent events inside an project
    *
    * @param selector
    *   to stream only events from a subset of the entity types
    * @param project
    *   the project reference
    * @param offset
    *   the offset to start from
    */
  def streamBy(selector: Label, project: ProjectRef, offset: Offset): IO[ServerSentEventStream]

  /**
    * Returns SSE selectors related to ScopedEvents
    */
  def selectors: Set[Label]
}

object SseEventLog {

  private val logger = Logger[SseEventLog]

  private[sse] def toServerSentEvent(
      envelope: Envelope[SseData]
  )(implicit jo: JsonKeyOrdering): ServerSentEvent = {
    val data = envelope.value.data
    envelope.offset match {
      case Start     => ServerSentEvent(defaultPrinter.print(data.asJson.sort), envelope.value.tpe)
      case At(value) => ServerSentEvent(defaultPrinter.print(data.asJson.sort), envelope.value.tpe, value.toString)
    }
  }

  def apply(
      sseEncoders: Set[SseEncoder[_]],
      fetchOrg: Label => IO[Unit],
      fetchProject: ProjectRef => IO[Unit],
      config: SseConfig,
      xas: Transactors
  )(implicit jo: JsonKeyOrdering): IO[SseEventLog] =
    IO.pure {
      new SseEventLog {
        implicit private val multiDecoder: MultiDecoder[SseData]        =
          MultiDecoder(sseEncoders.map { encoder => encoder.entityType -> encoder.toSse }.toMap)

        private val entityTypesBySelector: Map[Label, List[EntityType]] = sseEncoders
          .flatMap { encoder => encoder.selectors.map(_ -> encoder.entityType) }
          .groupMap(_._1)(_._2)
          .map { case (k, v) => k -> v.toList }

        override val selectors: Set[Label]                              = sseEncoders.flatMap(_.selectors)

        private def stream(scope: Scope, selector: Option[Label], offset: Offset): Stream[IO, ServerSentEvent] = {
          Stream
            .fromEither[IO](
              selector
                .map { l =>
                  entityTypesBySelector.get(l).toRight(UnknownSseLabel(l))
                }
                .getOrElse(Right(List.empty))
            )
            .flatMap { entityTypes =>
              EventStreaming
                .fetchScoped(
                  scope,
                  entityTypes,
                  offset,
                  config.query,
                  xas
                )
                .map(toServerSentEvent)
            }
        }

        override def streamBy(selector: Label, offset: Offset): Stream[IO, ServerSentEvent] =
          stream(Scope.root, Some(selector), offset)

        override def stream(org: Label, offset: Offset): IO[Stream[IO, ServerSentEvent]] =
          fetchOrg(org).as(stream(Scope.Org(org), None, offset))

        override def streamBy(selector: Label, org: Label, offset: Offset): IO[Stream[IO, ServerSentEvent]] =
          fetchOrg(org).as(stream(Scope.Org(org), Some(selector), offset))

        override def stream(project: ProjectRef, offset: Offset): IO[Stream[IO, ServerSentEvent]] =
          fetchProject(project).as(stream(Scope.Project(project), None, offset))

        override def streamBy(selector: Label, project: ProjectRef, offset: Offset): IO[Stream[IO, ServerSentEvent]] =
          fetchProject(project).as(stream(Scope.Project(project), Some(selector), offset))
      }
    }.flatTap { sseLog =>
      logger.info(s"SseLog is configured with selectors: ${sseLog.selectors.mkString("'", "','", "'")}")
    }

}
