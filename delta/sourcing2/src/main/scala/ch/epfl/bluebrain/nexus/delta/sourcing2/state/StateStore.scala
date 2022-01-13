package ch.epfl.bluebrain.nexus.delta.sourcing2.state

import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.sourcing2.Transactors
import ch.epfl.bluebrain.nexus.delta.sourcing2.config.TrackQueryConfig
import ch.epfl.bluebrain.nexus.delta.sourcing2.decoder.PayloadDecoder
import ch.epfl.bluebrain.nexus.delta.sourcing2.model.{EntityId, EntityType, Envelope}
import ch.epfl.bluebrain.nexus.delta.sourcing2.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing2.query.RefreshStrategy
import ch.epfl.bluebrain.nexus.delta.sourcing2.track.{SelectedTrack, Track, TrackStore}
import doobie._
import doobie.implicits._
import doobie.postgres.circe.jsonb.implicits._
import doobie.postgres.implicits._
import fs2.{Chunk, Stream}
import io.circe.Json
import monix.bio.Task

import java.time.Instant

/**
  * Fetches/saves states from/to the database
  */
trait StateStore {

  /**
    * Persists the state to the database
    */
  def save(row: StateRow): ConnectionIO[Unit]

  /**
    * Delete the state for the given tag
    */
  def deleteTagged(tpe: EntityType, id: EntityId, tag: String): ConnectionIO[Boolean]

  /**
    * Fetches the state with the given tag
    */
  def tagged[State](tpe: EntityType, id: EntityId, tag: String)(implicit
      decoder: PayloadDecoder[State]
  ): Task[Option[State]]

  /**
    * Fetches the state with the given tag
    */
  def latestState[State](tpe: EntityType, id: EntityId)(implicit decoder: PayloadDecoder[State]): Task[Option[State]]

  /**
    * Fetches states that belongs to the provided track from the provided offset
    *
    * The stream is completed when it reached the end .
    *
    * @param track
    *   the track to query
    * @param offset
    *   the offset to start from
    */
  def currentStatesByTrack[State](track: Track, offset: Offset)(implicit
      decoder: PayloadDecoder[State]
  ): Stream[Task, Envelope[State]]

  /**
    * Fetches states that belongs to the provided track from the provided offset
    *
    * The stream is not completed when it reaches the end of the existing events, but it continues to push new events
    * when new events are persisted.
    *
    * @param track
    *   the track to query
    * @param offset
    *   the offset to start from
    */
  def statesByTrack[State](track: Track, offset: Offset)(implicit
      decoder: PayloadDecoder[State]
  ): Stream[Task, Envelope[State]]

}

object StateStore {

  final class StateStoreImpl(trackStore: TrackStore, config: TrackQueryConfig, xas: Transactors) extends StateStore {

    override def save(row: StateRow): ConnectionIO[Unit] =
      sql"""
           | INSERT INTO states (
           |  event_type,
           |  entity_id,
           |  revision,
           |  payload,
           |  tracks,
           |  tag,
           |  updated_at,
           |  written_at,
           |  write_version
           | )
           | VALUES (
           |  ${row.tpe},
           |  ${row.id},
           |  ${row.revision},
           |  ${row.payload},
           |  ${row.tracks},
           |  ${row.tag},
           |  ${row.updatedAt},
           |  ${row.writtenAt},
           |  ${row.writeVersion}
           | )
           | ON CONFLICT UPDATE
           | SET
           |  revision      = EXCLUDED.revision,
           |  payload       = EXCLUDED.payload,
           |  updated_at    = EXCLUDED.updated_at,
           |  written_at    = EXCLUDED.written_at,
           |  write_version = EXCLUDED.write_version,
           |  ordering      = select nextval() from states_ordering_seq
         """.stripMargin.update.run.void

    override def deleteTagged(tpe: EntityType, id: EntityId, tag: String): ConnectionIO[Boolean] =
      sql"""
           | DELETE FROM states
           | WHERE event_type = $tpe
           | AND entity_id = $id
           | AND tag = $tag
        """.stripMargin.update.run.map(_ > 0)

    private def state[State](tpe: EntityType, id: EntityId, tag: Option[String])(implicit
        decoder: PayloadDecoder[State]
    ): Task[Option[State]] = {
      val select = fr"SELECT payload FROM states WHERE entity_type = $tpe AND entity_id = $id"
      val byTag  = tag.fold(fr" AND tag IS NULL")(t => fr"AND tag = $t")
      (select ++ byTag).query[Json].option.transact(xas.read).flatMap {
        case Some(json) => Task.fromEither(decoder(tpe, json)).map(Some(_))
        case None       => Task.none
      }
    }

    override def tagged[State](tpe: EntityType, id: EntityId, tag: String)(implicit
        decoder: PayloadDecoder[State]
    ): Task[Option[State]] =
      state(tpe, id, Some(tag))

    override def latestState[State](tpe: EntityType, id: EntityId)(implicit
        decoder: PayloadDecoder[State]
    ): Task[Option[State]] = state(tpe, id, None)

    private def statesByTrack[State](
        track: Track,
        offset: Offset,
        strategy: RefreshStrategy
    )(implicit decoder: PayloadDecoder[State]): Stream[Task, Envelope[State]] = {
      val select = fr"SELECT entity_type, entity_id, payload, revision, updated_at, ordering FROM states"

      Stream
        .eval(trackStore.select(track))
        .flatMap {
          case SelectedTrack.NotFound                  => Stream.empty
          case selectedTrack: SelectedTrack.ValidTrack =>
            Stream.unfoldChunkEval[Task, Offset, Envelope[State]](offset) { currentOffset =>
              val query =
                select ++ Fragments.whereAndOpt(selectedTrack.in, currentOffset.after) ++
                  fr"ORDER BY ordering" ++
                  fr"LIMIT ${config.batchSize}"

              query.query[(EntityType, EntityId, Json, Int, Instant, Long)].to[List].transact(xas.tracking).flatMap {
                rows =>
                  Task
                    .fromEither(
                      rows
                        .traverse { case (entityType, entityId, payload, rev, updatedAt, offset) =>
                          decoder(entityType, payload).map { state =>
                            Envelope(
                              entityType,
                              entityId,
                              state,
                              rev,
                              updatedAt,
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
                            Task.sleep(value) >> Task.some((Chunk.empty[Envelope[State]], currentOffset))
                        }
                      ) { last => Task.some((Chunk.seq(envelopes), last.offset)) }
                    }
              }
            }
        }
    }

    override def currentStatesByTrack[State](track: Track, offset: Offset)(implicit
        decoder: PayloadDecoder[State]
    ): Stream[Task, Envelope[State]] =
      statesByTrack(track, offset, RefreshStrategy.Stop)

    override def statesByTrack[State](track: Track, offset: Offset)(implicit
        decoder: PayloadDecoder[State]
    ): Stream[Task, Envelope[State]] =
      statesByTrack(track, offset, RefreshStrategy.Delay(config.refreshInterval))
  }

  def apply(trackStore: TrackStore, config: TrackQueryConfig, xas: Transactors): StateStore =
    new StateStoreImpl(trackStore, config, xas)

}
