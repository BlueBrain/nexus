package ch.epfl.bluebrain.nexus.delta.sourcing.event

import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.sourcing.Serializer
import ch.epfl.bluebrain.nexus.delta.sourcing.config.QueryConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.event.Event.ScopedEvent
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EntityType, ProjectRef}
import doobie._
import ch.epfl.bluebrain.nexus.delta.sourcing.implicits._
import doobie.syntax.all._
import doobie.postgres.implicits._
import fs2.Stream

/**
  * A
  */
trait ScopedEventStore[Id, E <: ScopedEvent] {

  /**
    * @param event
    *   The event to persist
    * @return
    *   A [[ConnectionIO]] describing how to persist the event.
    */
  def save(event: E): ConnectionIO[Unit]

  /**
    * Fetches the history for the event up to the provided revision
    */
  def history(ref: ProjectRef, id: Id, to: Option[Int]): Stream[ConnectionIO, E]

  /**
    * Fetches the history for the event up to the provided revision
    */
  def history(ref: ProjectRef, id: Id, to: Int): Stream[ConnectionIO, E] = history(ref, id, Some(to))

  /**
    * Fetches the history for the global event up to the last existing revision
    */
  def history(ref: ProjectRef, id: Id): Stream[ConnectionIO, E] = history(ref, id, None)

}

object ScopedEventStore {

  def apply[Id, E <: ScopedEvent](
      tpe: EntityType,
      serializer: Serializer[Id, E],
      config: QueryConfig
  ): ScopedEventStore[Id, E] =
    new ScopedEventStore[Id, E] {
      implicit val putId: Put[Id]   = serializer.putId
      implicit val getValue: Get[E] = serializer.getValue
      implicit val putValue: Put[E] = serializer.putValue

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
             |  ${event.id},
             |  ${event.rev},
             |  $event,
             |  ${event.instant}
             | )
       """.stripMargin.update.run.void

      override def history(ref: ProjectRef, id: Id, to: Option[Int]): Stream[ConnectionIO, E] = {
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

        select.query[E].streamWithChunkSize(config.batchSize)
      }
    }
}
