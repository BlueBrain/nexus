package ch.epfl.bluebrain.nexus.delta.sourcing.event

import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.database.Transactors
import ch.epfl.bluebrain.nexus.delta.sourcing.config.QueryConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.event.Event.ScopedEvent
import ch.epfl.bluebrain.nexus.delta.sourcing.implicits.IriInstances
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EntityType, Envelope, EnvelopeStream, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.query.{RefreshStrategy, StreamingQuery}
import ch.epfl.bluebrain.nexus.delta.sourcing.{Predicate, Serializer}
import ch.epfl.bluebrain.nexus.delta.sourcing.Partition._
import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import fs2.Stream
import io.circe.Decoder
import monix.bio.Task

/**
  * A
  */
trait ScopedEventStore[Id, E <: ScopedEvent] {

  /**
    * Persist the event. Attempts CREATE necessary partitions each time.
    */
  def save(event: E): ConnectionIO[Unit]

  /**
    * Persist the event. Attempts to CREATE partitions only if the necessary partition is not already in the cache.
    */
  def save(event: E, cache: Set[String]): ConnectionIO[Unit]

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
  def currentEvents(predicate: Predicate, offset: Offset): EnvelopeStream[E]

  /**
    * Allow to stream all current events within [[Envelope]] s
    * @param predicate
    *   to filter returned events
    * @param offset
    *   offset to start from
    */
  def events(predicate: Predicate, offset: Offset): EnvelopeStream[E]

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

      override def save(event: E, cache: Set[String]): doobie.ConnectionIO[Unit] =
        if (!cache.contains(projectRefHash(event.project))) save(event)
        else insertEvent(event)

      override def save(event: E): doobie.ConnectionIO[Unit] =
        createPartitions("scoped_events", event.project) >>
          insertEvent(event)

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

        select.query[E].streamWithChunkSize(config.batchSize).transact(xas.read)
      }

      private def events(
          predicate: Predicate,
          offset: Offset,
          strategy: RefreshStrategy
      ): Stream[Task, Envelope[E]] =
        StreamingQuery[Envelope[E]](
          offset,
          offset => sql"""SELECT type, id, value, rev, instant, ordering FROM public.scoped_events
                         |${Fragments.whereAndOpt(Some(fr"type = $tpe"), predicate.asFragment, offset.asFragment)}
                         |ORDER BY ordering
                         |LIMIT ${config.batchSize}""".stripMargin.query[Envelope[E]],
          _.offset,
          config.copy(refreshStrategy = strategy),
          xas
        )

      override def currentEvents(predicate: Predicate, offset: Offset): Stream[Task, Envelope[E]] =
        events(predicate, offset, RefreshStrategy.Stop)

      override def events(predicate: Predicate, offset: Offset): Stream[Task, Envelope[E]] =
        events(predicate, offset, config.refreshStrategy)

    }
}
