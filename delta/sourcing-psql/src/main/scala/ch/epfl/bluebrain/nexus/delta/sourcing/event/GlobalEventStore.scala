package ch.epfl.bluebrain.nexus.delta.sourcing.event

import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.sourcing.{Serializer, Transactors}
import ch.epfl.bluebrain.nexus.delta.sourcing.config.QueryConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.event.Event.GlobalEvent
import ch.epfl.bluebrain.nexus.delta.sourcing.implicits._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EntityType, Envelope, EnvelopeStream}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.query.{RefreshStrategy, StreamingQuery}
import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import fs2.Stream
import io.circe.Decoder
import monix.bio.Task

/**
  * Allows to save and fetch [[GlobalEvent]] s from the database
  */
trait GlobalEventStore[Id, E <: GlobalEvent] {

  /**
    * Persist the event
    */
  def save(event: E): ConnectionIO[Unit]

  /**
    * Delete all events for the given id
    */
  def delete(id: Id): ConnectionIO[Unit]

  /**
    * Fetches the history for the global event up to the provided revision
    */
  def history(id: Id, to: Option[Int]): Stream[Task, E]

  /**
    * Fetches the history for the global event up to the provided revision
    */
  def history(id: Id, to: Int): Stream[Task, E] = history(id, Some(to))

  /**
    * Fetches the history for the global event up to the last existing revision
    */
  def history(id: Id): Stream[Task, E] = history(id, None)

  /**
    * Fetches events from the given type from the provided offset.
    *
    * The stream is completed when it reaches the end .
    *
    * @param offset
    *   the offset
    */
  def currentEvents(offset: Offset): EnvelopeStream[E]

  /**
    * Fetches events from the given type from the provided offset
    *
    * The stream is not completed when it reaches the end of the existing events, but it continues to push new events
    * when new events are persisted.
    *
    * @param offset
    *   the offset
    */
  def events(offset: Offset): EnvelopeStream[E]

}

object GlobalEventStore {

  def apply[Id, E <: GlobalEvent](
      tpe: EntityType,
      serializer: Serializer[Id, E],
      config: QueryConfig,
      xas: Transactors
  ): GlobalEventStore[Id, E] =
    new GlobalEventStore[Id, E] {

      import IriInstances._
      implicit val putId: Put[Id]      = serializer.putId
      implicit val getValue: Get[E]    = serializer.getValue
      implicit val putValue: Put[E]    = serializer.putValue
      implicit val decoder: Decoder[E] = serializer.codec

      override def save(event: E): ConnectionIO[Unit] =
        sql"""
           | INSERT INTO global_events (
           |  type,
           |  id,
           |  rev,
           |  value,
           |  instant
           | )
           | VALUES (
           |  $tpe,
           |  ${event.id},
           |  ${event.rev},
           |  $event,
           |  ${event.instant}
           | )
         """.stripMargin.update.run.void

      def delete(id: Id): ConnectionIO[Unit] =
        sql"""DELETE FROM global_events where type = $tpe and id = $id""".update.run.void

      override def history(id: Id, to: Option[Int]): Stream[Task, E] = {
        val select =
          fr"SELECT value FROM public.global_events" ++
            Fragments.whereAndOpt(Some(fr"type = $tpe"), Some(fr"id = $id"), to.map { t => fr" rev <= $t" }) ++
            fr"ORDER BY rev"

        select.query[E].streamWithChunkSize(config.batchSize).transact(xas.read)
      }

      private def events(offset: Offset, strategy: RefreshStrategy): Stream[Task, Envelope[E]] =
        StreamingQuery[Envelope[E]](
          offset,
          offset => sql"""SELECT type, id, value, rev, instant, ordering FROM public.global_events
                         |${Fragments.whereAndOpt(Some(fr"type = $tpe"), offset.asFragment)}
                         |ORDER BY ordering
                         |LIMIT ${config.batchSize}""".stripMargin.query[Envelope[E]],
          _.offset,
          config.copy(refreshStrategy = strategy),
          xas
        )

      override def currentEvents(offset: Offset): Stream[Task, Envelope[E]] = events(offset, RefreshStrategy.Stop)

      override def events(offset: Offset): Stream[Task, Envelope[E]] = events(offset, config.refreshStrategy)
    }

}
