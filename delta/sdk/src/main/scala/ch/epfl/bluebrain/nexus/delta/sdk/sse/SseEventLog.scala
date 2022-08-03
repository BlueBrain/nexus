package ch.epfl.bluebrain.nexus.delta.sdk.sse

import akka.http.scaladsl.model.sse.ServerSentEvent
import ch.epfl.bluebrain.nexus.delta.kernel.database.Transactors
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.cache.KeyValueStore
import ch.epfl.bluebrain.nexus.delta.sdk.error.ServiceError.UnknownSseLabel
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.RdfMarshalling.defaultPrinter
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.model.OrganizationRejection
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ProjectRejection
import ch.epfl.bluebrain.nexus.delta.sdk.sse.SseEncoder.SseData
import ch.epfl.bluebrain.nexus.delta.sdk.sse.SseEventLog.ServerSentEventStream
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sourcing.event.EventStreaming
import ch.epfl.bluebrain.nexus.delta.sourcing.model._
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset.{At, Start}
import ch.epfl.bluebrain.nexus.delta.sourcing.{MultiDecoder, Predicate}
import fs2.Stream
import io.circe.syntax.EncoderOps
import monix.bio.{IO, Task, UIO}

import java.util.UUID

/**
  * An event log that reads events from a [[Stream]] and transforms each event to JSON in preparation for consumption by
  * SSE routes
  */
trait SseEventLog {

  /**
    * Get stream of server sent events
    *
    * @param offset
    *   the offset to start from
    */
  def stream(offset: Offset): ServerSentEventStream

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
  ): IO[OrganizationRejection, ServerSentEventStream]

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
  def streamBy(selector: Label, org: Label, offset: Offset): IO[OrganizationRejection, ServerSentEventStream]

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
  ): IO[ProjectRejection, ServerSentEventStream]

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
  def streamBy(selector: Label, project: ProjectRef, offset: Offset): IO[ProjectRejection, ServerSentEventStream]

  /**
    * Return all SSE selectors
    */
  def allSelectors: Set[Label]

  /**
    * Returns SSE selectors related to ScopedEvents
    */
  def scopedSelectors: Set[Label]
}

object SseEventLog {

  type ServerSentEventStream = Stream[Task, ServerSentEvent]

  private[sse] def toServerSentEvent(
      envelope: Envelope[String, SseData],
      fetchUuids: ProjectRef => UIO[Option[(UUID, UUID)]]
  )(implicit jo: JsonKeyOrdering): UIO[ServerSentEvent] = {
    val data = envelope.value.data
    envelope.value.project
      .fold(UIO.pure(data)) { ref =>
        fetchUuids(ref).map {
          _.fold(data) { case (orgUuid, projUuid) =>
            data.add("_organizationUuid", orgUuid.asJson).add("_projectUuid", projUuid.asJson)
          }
        }
      }
      .map { json =>
        envelope.offset match {
          case Start     => ServerSentEvent(defaultPrinter.print(json.asJson.sort), envelope.value.tpe)
          case At(value) => ServerSentEvent(defaultPrinter.print(json.asJson.sort), envelope.value.tpe, value.toString)
        }
      }
  }

  def apply(
      sseEncoders: Set[SseEncoder[_]],
      fetchOrg: Label => IO[OrganizationRejection, Unit],
      fetchProject: ProjectRef => IO[ProjectRejection, (UUID, UUID)],
      config: SseConfig,
      xas: Transactors
  )(implicit jo: JsonKeyOrdering): UIO[SseEventLog] =
    KeyValueStore.localLRU[ProjectRef, (UUID, UUID)](config.cache).map { cache =>
      new SseEventLog {
        implicit private val multiDecoder: MultiDecoder[SseData]        =
          MultiDecoder(sseEncoders.map { encoder => encoder.entityType -> encoder.toSse }.toMap)

        private val entityTypesBySelector: Map[Label, List[EntityType]] = sseEncoders
          .flatMap { encoder => encoder.selectors.map(_ -> encoder.entityType) }
          .groupMap(_._1)(_._2)
          .map { case (k, v) => k -> v.toList }

        private def fetchUuids(ref: ProjectRef)                         =
          cache.getOrElseUpdate(ref, fetchProject(ref)).attempt.map(_.toOption)

        private def stream(predicate: Predicate, selector: Option[Label], offset: Offset)
            : Stream[Task, ServerSentEvent] = {
          Stream
            .fromEither[Task](
              selector
                .map { l =>
                  entityTypesBySelector.get(l).toRight(UnknownSseLabel(l))
                }
                .getOrElse(Right(List.empty))
            )
            .flatMap { entityTypes =>
              EventStreaming
                .fetchAll(
                  predicate,
                  entityTypes,
                  offset,
                  config.query,
                  xas
                )
                .evalMap(toServerSentEvent(_, fetchUuids))
            }
        }

        override def stream(offset: Offset): Stream[Task, ServerSentEvent] = stream(Predicate.root, None, offset)

        override def streamBy(selector: Label, offset: Offset): Stream[Task, ServerSentEvent] =
          stream(Predicate.root, Some(selector), offset)

        override def stream(org: Label, offset: Offset): IO[OrganizationRejection, Stream[Task, ServerSentEvent]] =
          fetchOrg(org).as(stream(Predicate.Org(org), None, offset))

        override def streamBy(selector: Label, org: Label, offset: Offset)
            : IO[OrganizationRejection, Stream[Task, ServerSentEvent]] =
          fetchOrg(org).as(stream(Predicate.Org(org), Some(selector), offset))

        override def stream(project: ProjectRef, offset: Offset): IO[ProjectRejection, Stream[Task, ServerSentEvent]] =
          fetchProject(project).as(stream(Predicate.Project(project), None, offset))

        override def streamBy(selector: Label, project: ProjectRef, offset: Offset)
            : IO[ProjectRejection, Stream[Task, ServerSentEvent]] =
          fetchProject(project).as(stream(Predicate.Project(project), Some(selector), offset))

        override def allSelectors: Set[Label] = sseEncoders.flatMap(_.selectors)

        override def scopedSelectors: Set[Label] = sseEncoders.flatMap { encoder =>
          if (encoder.handlesScopedEvent) encoder.selectors else Set.empty
        }
      }
    }

}
