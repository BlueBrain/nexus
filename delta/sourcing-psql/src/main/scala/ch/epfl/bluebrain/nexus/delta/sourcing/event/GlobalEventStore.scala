package ch.epfl.bluebrain.nexus.delta.sourcing.event

import cats.effect.IO
import cats.syntax.all.*
import ch.epfl.bluebrain.nexus.delta.sourcing.config.QueryConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.event.Event.GlobalEvent
import ch.epfl.bluebrain.nexus.delta.sourcing.model.EntityType
import ch.epfl.bluebrain.nexus.delta.sourcing.{Serializer, Transactors}
import ch.epfl.bluebrain.nexus.delta.sourcing.implicits.*

import doobie.*
import doobie.postgres.implicits.*

import doobie.syntax.all.*
import fs2.Stream

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
  def history(id: Id, to: Option[Int]): Stream[IO, E]

  /**
    * Fetches the history for the global event up to the provided revision
    */
  final def history(id: Id, to: Int): Stream[IO, E] = history(id, Some(to))

  /**
    * Fetches the history for the global event up to the last existing revision
    */
  final def history(id: Id): Stream[IO, E] = history(id, None)
}

object GlobalEventStore {

  def apply[Id, E <: GlobalEvent](
      tpe: EntityType,
      serializer: Serializer[Id, E],
      config: QueryConfig,
      xas: Transactors
  ): GlobalEventStore[Id, E] =
    new GlobalEventStore[Id, E] {
      implicit val putId: Put[Id]   = serializer.putId
      implicit val getValue: Get[E] = serializer.getValue
      implicit val putValue: Put[E] = serializer.putValue

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

      override def history(id: Id, to: Option[Int]): Stream[IO, E] = {
        val select =
          fr"SELECT value FROM public.global_events" ++
            Fragments.whereAndOpt(Some(fr"type = $tpe"), Some(fr"id = $id"), to.map { t => fr" rev <= $t" }) ++
            fr"ORDER BY rev"

        select.query[E].streamWithChunkSize(config.batchSize).transact(xas.read)
      }
    }

}
