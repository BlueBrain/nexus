package ch.epfl.bluebrain.nexus.delta.sourcing.event

import cats.effect.IO
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.sourcing.config.QueryConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.event.Event.ScopedEvent
import ch.epfl.bluebrain.nexus.delta.sourcing.implicits._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EntityType, Envelope, EnvelopeStream, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.query.{RefreshStrategy, StreamingQuery}
import ch.epfl.bluebrain.nexus.delta.sourcing.{Execute, PartitionInit, Scope, Serializer, Transactors}
import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import fs2.Stream
import io.circe.Decoder

/**
  * A
  */
trait ScopedEventStore[Id, E <: ScopedEvent] {

  /**
    * @param event
    *   The event to persist
    * @param init
    *   The type of partition initialization to run prior to persisting
    * @return
    *   A [[ConnectionIO]] describing how to persist the event.
    */
  def save(event: E, init: PartitionInit): ConnectionIO[Unit]

  /**
    * Persist the event with forced partition initialization. Forcing partition initialization can have a negative
    * impact on performance.
    */
  def unsafeSave(event: E): ConnectionIO[Unit] =
    save(event, Execute(event.project))

  /**
    * Fetches the history for the event up to the provided revision
    */
  def history(ref: ProjectRef, id: Id, to: Option[Int]): Stream[IO, E]

  /**
    * Fetches the history for the event up to the provided revision
    */
  def history(ref: ProjectRef, id: Id, to: Int): Stream[IO, E] = history(ref, id, Some(to))

  /**
    * Fetches the history for the global event up to the last existing revision
    */
  def history(ref: ProjectRef, id: Id): Stream[IO, E] = history(ref, id, None)

  /**
    * Allow to stream all current events within [[Envelope]] s
    * @param scope
    *   to filter returned events
    * @param offset
    *   offset to start from
    */
  def currentEvents(scope: Scope, offset: Offset): EnvelopeStream[E]

  /**
    * Allow to stream all current events within [[Envelope]] s
    * @param scope
    *   to filter returned events
    * @param offset
    *   offset to start from
    */
  def events(scope: Scope, offset: Offset): EnvelopeStream[E]

}

object ScopedEventStore {

  def apply[Id, E <: ScopedEvent](
      tpe: EntityType,
      serializer: Serializer[Id, E],
      config: QueryConfig,
      xas: Transactors
  ): ScopedEventStore[Id, E] =
    new ScopedEventStore[Id, E] {

      import IriInstances._
      implicit val putId: Put[Id]      = serializer.putId
      implicit val getValue: Get[E]    = serializer.getValue
      implicit val putValue: Put[E]    = serializer.putValue
      implicit val decoder: Decoder[E] = serializer.codec

      private def insertEvent(event: E) =
        sql"""
             | INSERT INTO scoped_events (
             |  type,
             |  org,
             |  project,
             |  id,
             |  rev,
             |  value,
             |  instant
             | )
             | VALUES (
             |  $tpe,
             |  ${event.organization},
             |  ${event.project.project},
             |  ${event.id},
             |  ${event.rev},
             |  $event,
             |  ${event.instant}
             | )
       """.stripMargin.update.run.void

      override def save(event: E, init: PartitionInit): doobie.ConnectionIO[Unit] =
        init.initializePartition("scoped_events") >> insertEvent(event)

      override def history(ref: ProjectRef, id: Id, to: Option[Int]): Stream[IO, E] = {
        val select =
          fr"SELECT value FROM scoped_events" ++
            Fragments.whereAndOpt(
              Some(fr"type = $tpe"),
              Some(fr"org = ${ref.organization}"),
              Some(fr"project = ${ref.project}"),
              Some(fr"id = $id"),
              to.map { t => fr" rev <= $t" }
            ) ++
            fr"ORDER BY rev"

        select.query[E].streamWithChunkSize(config.batchSize).transact(xas.read)
      }

      private def events(
          scope: Scope,
          offset: Offset,
          strategy: RefreshStrategy
      ): EnvelopeStream[E] =
        StreamingQuery[Envelope[E]](
          offset,
          offset => sql"""SELECT type, id, value, rev, instant, ordering FROM public.scoped_events
                         |${Fragments.whereAndOpt(Some(fr"type = $tpe"), scope.asFragment, offset.asFragment)}
                         |ORDER BY ordering
                         |LIMIT ${config.batchSize}""".stripMargin.query[Envelope[E]],
          _.offset,
          config.copy(refreshStrategy = strategy),
          xas
        )

      override def currentEvents(scope: Scope, offset: Offset): EnvelopeStream[E] =
        events(scope, offset, RefreshStrategy.Stop)

      override def events(scope: Scope, offset: Offset): EnvelopeStream[E] =
        events(scope, offset, config.refreshStrategy)

    }
}
