package ch.epfl.bluebrain.nexus.delta.sourcing.state

import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.database.Transactors
import ch.epfl.bluebrain.nexus.delta.sourcing.Serializer
import ch.epfl.bluebrain.nexus.delta.sourcing.config.QueryConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EntityType, Envelope, EnvelopeStream}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.query.RefreshStrategy
import ch.epfl.bluebrain.nexus.delta.sourcing.state.State.GlobalState
import doobie._
import doobie.implicits._
import doobie.postgres.circe.jsonb.implicits._
import doobie.postgres.implicits._
import doobie.util.transactor.Transactor
import fs2.Stream
import io.circe.Json
import io.circe.syntax.EncoderOps
import monix.bio.{Task, UIO}

/**
  * Allow to save and fetch [[GlobalState]] s from the database
  */
trait GlobalStateStore[Id, S <: GlobalState] {

  /**
    * Persist the state at the given tag
    */
  def save(state: S): ConnectionIO[Unit]

  /**
    * Delete the state
    */
  def delete(id: Id): ConnectionIO[Unit]

  /**
    * Returns the state
    */
  def get(id: Id): UIO[Option[S]]

  /**
    * Fetches states from the given type from the provided offset.
    *
    * The stream is completed when it reaches the end.
    *
    * @param offset
    *   the offset
    */
  def currentStates(offset: Offset): EnvelopeStream[Id, S]

  /**
    * Fetches states from the given type from the provided offset
    *
    * The stream is not completed when it reaches the end of the existing events, but it continues to push new events
    * when new events are persisted.
    *
    * @param offset
    *   the offset
    */
  def states(offset: Offset): EnvelopeStream[Id, S]

}

object GlobalStateStore {

  def listIds[Id](tpe: EntityType, xa: Transactor[Task])(implicit getId: Get[Id]): Stream[Task, Id] =
    sql"SELECT id FROM global_states WHERE type = $tpe".query[Id].stream.transact(xa)

  def apply[Id, S <: GlobalState](
      tpe: EntityType,
      serializer: Serializer[Id, S],
      config: QueryConfig,
      xas: Transactors
  )(implicit getId: Get[Id], putId: Put[Id]): GlobalStateStore[Id, S] = new GlobalStateStore[Id, S] {

    import serializer._

    override def save(state: S): ConnectionIO[Unit] = {
      val id = extractId(state)
      sql"SELECT 1 FROM global_states WHERE type = $tpe AND id = $id"
        .query[Int]
        .option
        .flatMap {
          _.fold(sql"""
              | INSERT INTO global_states (
              |  type,
              |  id,
              |  rev,
              |  value,
              |  instant
              | )
              | VALUES (
              |  $tpe,
              |  $id,
              |  ${state.rev},
              |  ${state.asJson},
              |  ${state.updatedAt}
              | )
            """.stripMargin) { _ =>
            sql"""
               | UPDATE global_states SET
               |  rev = ${state.rev},
               |  value = ${state.asJson},
               |  instant = ${state.updatedAt},
               |  ordering = (select nextval('global_states_ordering_seq'))
               | WHERE
               |  type = $tpe AND
               |  id = $id
            """.stripMargin
          }.update.run.void
        }
    }

    override def delete(id: Id): ConnectionIO[Unit] =
      sql"""DELETE FROM global_states WHERE type = $tpe AND id = $id""".stripMargin.update.run.void

    override def get(id: Id): UIO[Option[S]] =
      sql"""SELECT value FROM global_states WHERE type = $tpe AND id = $id"""
        .query[Json]
        .option
        .transact(xas.read)
        .hideErrors
        .flatMap {
          case Some(json) => Task.fromEither(json.as[S]).map(Some(_)).hideErrors
          case None       => UIO.none
        }

    private def states(offset: Offset, strategy: RefreshStrategy): EnvelopeStream[Id, S] =
      Envelope.stream(
        offset,
        offset => sql"""SELECT type, id, value, rev, instant, ordering FROM public.global_states
                       |${Fragments.whereAndOpt(Some(fr"type = $tpe"), offset.asFragment)}
                       |ORDER BY ordering""".stripMargin.query[Envelope[Id, S]],
        xas,
        config.copy(refreshStrategy = strategy)
      )

    override def currentStates(offset: Offset): EnvelopeStream[Id, S] = states(offset, RefreshStrategy.Stop)

    override def states(offset: Offset): EnvelopeStream[Id, S] = states(offset, config.refreshStrategy)
  }

}
