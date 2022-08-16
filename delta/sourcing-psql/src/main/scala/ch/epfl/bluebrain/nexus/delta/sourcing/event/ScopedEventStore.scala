package ch.epfl.bluebrain.nexus.delta.sourcing.event

import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.database.Transactors
import ch.epfl.bluebrain.nexus.delta.sourcing.config.QueryConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.event.Event.ScopedEvent
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EntityType, Envelope, EnvelopeStream, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.query.RefreshStrategy
import ch.epfl.bluebrain.nexus.delta.sourcing.{Predicate, Serializer}
import doobie._
import doobie.implicits._
import doobie.postgres.circe.jsonb.implicits._
import doobie.postgres.implicits._
import fs2.Stream
import io.circe.Json
import io.circe.syntax.EncoderOps
import monix.bio.Task

/**
  * A
  */
trait ScopedEventStore[Id, E <: ScopedEvent] {

  /**
    * Persist the event
    */
  def save(event: E): ConnectionIO[Unit]

  /**
    * Fetches the history for the event up to the provided revision
    */
  def history(ref: ProjectRef, id: Id, to: Option[Int]): Stream[Task, E]

  /**
    * Fetches the history for the event up to the provided revision
    */
  def history(ref: ProjectRef, id: Id, to: Int): Stream[Task, E] = history(ref, id, Some(to))

  /**
    * Fetches the history for the global event up to the last existing revision
    */
  def history(ref: ProjectRef, id: Id): Stream[Task, E] = history(ref, id, None)

  /**
    * Allow to stream all current events within [[Envelope]] s
    * @param predicate
    *   to filter returned events
    * @param offset
    *   offset to start from
    */
  def currentEvents(predicate: Predicate, offset: Offset): EnvelopeStream[Id, E]

  /**
    * Allow to stream all current events within [[Envelope]] s
    * @param predicate
    *   to filter returned events
    * @param offset
    *   offset to start from
    */
  def events(predicate: Predicate, offset: Offset): EnvelopeStream[Id, E]

}

object ScopedEventStore {

  def apply[Id, E <: ScopedEvent](
      tpe: EntityType,
      serializer: Serializer[Id, E],
      config: QueryConfig,
      xas: Transactors
  )(implicit
      get: Get[Id],
      put: Put[Id]
  ): ScopedEventStore[Id, E] =
    new ScopedEventStore[Id, E] {
      import serializer._

      override def save(event: E): doobie.ConnectionIO[Unit] =
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
           |  ${extractId(event)},
           |  ${event.rev},
           |  ${event.asJson},
           |  ${event.instant}
           | )
         """.stripMargin.update.run.void

      override def history(ref: ProjectRef, id: Id, to: Option[Int]): Stream[Task, E] = {
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

        select.query[Json].streamWithChunkSize(config.batchSize).transact(xas.read).flatMap { json =>
          Stream.fromEither[Task](json.as[E])
        }
      }

      private def events(
          predicate: Predicate,
          offset: Offset,
          strategy: RefreshStrategy
      ): Stream[Task, Envelope[Id, E]] =
        Envelope.stream(
          offset,
          (o: Offset) =>
            fr"SELECT type, id, value, rev, instant, ordering FROM public.scoped_events" ++
              Fragments.whereAndOpt(Some(fr"type = $tpe"), predicate.asFragment, o.asFragment) ++
              fr"ORDER BY ordering" ++
              fr"LIMIT ${config.batchSize}",
          strategy,
          xas
        )

      override def currentEvents(predicate: Predicate, offset: Offset): Stream[Task, Envelope[Id, E]] =
        events(predicate, offset, RefreshStrategy.Stop)

      override def events(predicate: Predicate, offset: Offset): Stream[Task, Envelope[Id, E]] =
        events(predicate, offset, config.refreshInterval)

    }
}
