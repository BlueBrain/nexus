package ch.epfl.bluebrain.nexus.delta.sourcing.event

import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.sourcing.Transactors
import ch.epfl.bluebrain.nexus.delta.sourcing.config.SourcingConfig.QueryConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.event.Event.{GlobalEvent, ScopedEvent}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EntityType, Envelope}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.query.RefreshStrategy
import ch.epfl.bluebrain.nexus.delta.sourcing.serialization.{ValueDecoder, ValueEncoder}
import doobie._
import doobie.implicits._
import doobie.postgres.circe.jsonb.implicits._
import doobie.postgres.implicits._
import fs2.{Chunk, Stream}
import io.circe.Json
import monix.bio.Task

import java.time.Instant
import scala.annotation.nowarn

trait EventStore {

  /**
    * Persist the event
    */
  def save[E <: Event](event: E)(implicit encoder: ValueEncoder[E]): ConnectionIO[Unit]

  /**
    * Fetches the history for the global event up to the provided revision
    */
  def globalEventHistory[Id, E <: GlobalEvent](tpe: EntityType, id: Id, to: Option[Int])(implicit
                                                                                         decoder: ValueDecoder[Id, E]
  ): Stream[Task, E]

  /**
    * Fetches the history for the global event up to the last existing revision
    */
  def globalEventHistory[Id, E <: GlobalEvent](tpe: EntityType, id: Id)(implicit
                                                                        decoder: ValueDecoder[Id, E]
  ): Stream[Task, E] =
    globalEventHistory(tpe, id, None)

  /**
    * Fetches events from the given type from the provided ofset
    *
    * @param tpe
    *   the type
    * @param offset
    *   the offset
    */
  def globalEvents[Id, E <: GlobalEvent](tpe: EntityType, offset: Offset)(implicit
                                                                          decoder: ValueDecoder[Id, E]
  ): Stream[Task, Envelope[Id, E]]

  /**
    * Fetches events from the given type from the provided offset
    *
    * The stream is not completed when it reaches the end of the existing events, but it continues to push new events
    * when new events are persisted.
    *
    * @param tpe
    *   the type
    * @param offset
    *   the offset
    */
  def events[Id, E <: GlobalEvent](tpe: EntityType, offset: Offset)(implicit
      getId: Get[Id],
      decoder: ValueDecoder[Id, E]
  ): Stream[Task, Envelope[Id, E]]

}

object EventStore {

  final private[event] class EventStoreImpl(config: QueryConfig, xas: Transactors) extends EventStore {

    override def save[E <: Event](event: E)(implicit encoder: ValueEncoder[E]): ConnectionIO[Unit] =
      event match {
        case _: GlobalEvent =>
          sql"""
           | INSERT INTO global_events (
           |  type,
           |  id,
           |  rev,
           |  value,
           |  instant,
           | )
           | VALUES (
           |  ${encoder.entityType},
           |  ${encoder.serializeId(event)},
           |  ${event.rev},
           |  ${encoder.encoder(event)},
           |  ${event.instant}
           | )
         """.stripMargin.update.run.void
        case s: ScopedEvent =>
          sql"""
               | INSERT INTO scoped_events (
               |  type,
               |  org,
               |  project,
               |  id,
               |  rev,
               |  value,
               |  instant,
               | )
               | VALUES (
               |  ${encoder.entityType},
               |  ${s.organization},
               |  ${s.project},
               |  ${encoder.serializeId(s)},
               |  ${event.rev},
               |  ${encoder.encoder(event)},
               |  ${event.instant}
               | )
         """.stripMargin.update.run.void
      }

    override def globalEventHistory[Id, E <: GlobalEvent](tpe: EntityType, id: Id, to: Option[Int])(implicit
                                                                                                    decoder: ValueDecoder[Id, E]
    ): Stream[Task, E] = {
      import decoder._
      val select =
        fr"SELECT value FROM public.global_events WHERE type = $tpe AND id = $id" ++
          Fragments.andOpt(to.map { t => fr"rev <= $t" }) ++
          fr"ORDER BY rev"

      select.query[Json].streamWithChunkSize(config.batchSize).transact(xas.read).flatMap { json =>
        Stream.fromEither[Task](decoder(tpe, json))
      }
    }

    @nowarn("cat=unused")
    private def events[Id, E <: Event](tpe: EntityType, offset: Offset, strategy: RefreshStrategy)(implicit
        decoder: ValueDecoder[Id, E]
    ): Stream[Task, Envelope[Id, E]] = {
      import decoder._
      val select = fr"SELECT type, id, rev, value, instant, ordering FROM events where type = $tpe"
      Stream.unfoldChunkEval[Task, Offset, Envelope[Id, E]](offset) { currentOffset =>
        val query =
          select ++ Fragments.andOpt(currentOffset.after) ++
            fr"ORDER BY ordering" ++
            fr"LIMIT ${config.batchSize}"

        query.query[(EntityType, Id, Json, Int, Instant, Long)].to[List].transact(xas.tracking).flatMap { rows =>
          Task
            .fromEither(
              rows
                .traverse { case (entityType, id, value, rev, instant, offset) =>
                  decoder(entityType, value).map { event =>
                    Envelope(
                      entityType,
                      id,
                      rev,
                      event,
                      instant,
                      Offset.At(offset)
                    )
                  }
                }
            )
            .flatMap { envelopes =>
              envelopes.lastOption.fold(
                strategy match {
                  case RefreshStrategy.Stop         => Task.none
                  case RefreshStrategy.Delay(value) =>
                    Task.sleep(value) >> Task.some((Chunk.empty[Envelope[Id, E]], currentOffset))
                }
              ) { last => Task.some((Chunk.seq(envelopes), last.offset)) }
            }
        }
      }
    }

    override def globalEvents[Id, E <: Event](tpe: EntityType, offset: Offset)(implicit
                                                                               decoder: ValueDecoder[Id, E]
    ): Stream[Task, Envelope[Id, E]] =
      events(tpe, offset, RefreshStrategy.Stop)

    override def events[Id, E <: Event](tpe: EntityType, offset: Offset)(implicit
        decoder: ValueDecoder[Id, E]
    ): Stream[Task, Envelope[Id, E]] =
      events(tpe, offset, config.refreshInterval)
  }

}
