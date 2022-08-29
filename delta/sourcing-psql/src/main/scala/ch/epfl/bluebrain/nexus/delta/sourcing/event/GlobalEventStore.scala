package ch.epfl.bluebrain.nexus.delta.sourcing.event

import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.database.Transactors
import ch.epfl.bluebrain.nexus.delta.sourcing.config.QueryConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.event.Event.GlobalEvent
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EntityType, Envelope, EnvelopeStream}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.query.RefreshStrategy
import ch.epfl.bluebrain.nexus.delta.sourcing.Serializer
import doobie._
import doobie.implicits._
import doobie.postgres.circe.jsonb.implicits._
import doobie.postgres.implicits._
import fs2.Stream
import io.circe.Json
import io.circe.syntax.EncoderOps
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
  def currentEvents(offset: Offset): EnvelopeStream[Id, E]

  /**
    * Fetches events from the given type from the provided offset
    *
    * The stream is not completed when it reaches the end of the existing events, but it continues to push new events
    * when new events are persisted.
    *
    * @param offset
    *   the offset
    */
  def events(offset: Offset): EnvelopeStream[Id, E]

}

object GlobalEventStore {

  def apply[Id, E <: GlobalEvent](
      tpe: EntityType,
      serializer: Serializer[Id, E],
      config: QueryConfig,
      xas: Transactors
  )(implicit getId: Get[Id], putId: Put[Id]): GlobalEventStore[Id, E] =
    new GlobalEventStore[Id, E] {

      import serializer._

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
           |  ${extractId(event)},
           |  ${event.rev},
           |  ${event.asJson},
           |  ${event.instant}
           | )
         """.stripMargin.update.run.void

      override def history(id: Id, to: Option[Int]): Stream[Task, E] = {
        val select =
          fr"SELECT value FROM public.global_events" ++
            Fragments.whereAndOpt(Some(fr"type = $tpe"), Some(fr"id = $id"), to.map { t => fr" rev <= $t" }) ++
            fr"ORDER BY rev"

        select.query[Json].streamWithChunkSize(config.batchSize).transact(xas.read).flatMap { json =>
          Stream.fromEither[Task](json.as[E])
        }
      }

      private def events(offset: Offset, strategy: RefreshStrategy): Stream[Task, Envelope[Id, E]] =
        Envelope.stream[Id, E](
          offset,
          offset => sql"""SELECT type, id, value, rev, instant, ordering FROM public.global_events
                         |${Fragments.whereAndOpt(Some(fr"type = $tpe"), offset.asFragment)}
                         |ORDER BY ordering""".stripMargin.query[Envelope[Id, E]],
          xas,
          config.copy(refreshStrategy = strategy)
        )

      override def currentEvents(offset: Offset): Stream[Task, Envelope[Id, E]] = events(offset, RefreshStrategy.Stop)

      override def events(offset: Offset): Stream[Task, Envelope[Id, E]] = events(offset, config.refreshStrategy)
    }

}
