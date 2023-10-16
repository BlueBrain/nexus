package ch.epfl.bluebrain.nexus.delta.sourcing.state

import cats.effect.IO
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sourcing.{Serializer, Transactors}
import ch.epfl.bluebrain.nexus.delta.sourcing.config.QueryConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.implicits._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EntityType, Envelope, EnvelopeStream}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.query.{RefreshStrategy, StreamingQuery}
import ch.epfl.bluebrain.nexus.delta.sourcing.state.State.GlobalState
import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.util.transactor.Transactor
import fs2.Stream
import io.circe.Decoder

/**
  * Allow to save and fetch [[GlobalState]] s from the database
  */
trait GlobalStateStore[Id, S <: GlobalState] {

  /**
    * Persist the state
    */
  def save(state: S): ConnectionIO[Unit]

  /**
    * Delete the state
    */
  def delete(id: Id): ConnectionIO[Unit]

  /**
    * Returns the state
    */
  def get(id: Id): IO[Option[S]]

  /**
    * Fetches states from the given type from the provided offset.
    *
    * The stream is completed when it reaches the end.
    *
    * @param offset
    *   the offset
    */
  def currentStates(offset: Offset): EnvelopeStream[S]

  /**
    * Fetches states from the given type from the provided offset
    *
    * The stream is not completed when it reaches the end of the existing events, but it continues to push new events
    * when new events are persisted.
    *
    * @param offset
    *   the offset
    */
  def states(offset: Offset): EnvelopeStream[S]

}

object GlobalStateStore {

  def listIds(tpe: EntityType, xa: Transactor[IO]): Stream[IO, Iri] =
    sql"SELECT id FROM global_states WHERE type = $tpe".query[Iri].stream.transact(xa)

  def apply[Id, S <: GlobalState](
      tpe: EntityType,
      serializer: Serializer[Id, S],
      config: QueryConfig,
      xas: Transactors
  ): GlobalStateStore[Id, S] = new GlobalStateStore[Id, S] {

    import IriInstances._
    implicit val putId: Put[Id]      = serializer.putId
    implicit val getValue: Get[S]    = serializer.getValue
    implicit val putValue: Put[S]    = serializer.putValue
    implicit val decoder: Decoder[S] = serializer.codec

    override def save(state: S): ConnectionIO[Unit] = {
      sql"SELECT 1 FROM global_states WHERE type = $tpe AND id = ${state.id}"
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
              |  ${state.id},
              |  ${state.rev},
              |  $state,
              |  ${state.updatedAt}
              | )
            """.stripMargin) { _ =>
            sql"""
               | UPDATE global_states SET
               |  rev = ${state.rev},
               |  value = $state,
               |  instant = ${state.updatedAt},
               |  ordering = (select nextval('state_offset'))
               | WHERE
               |  type = $tpe AND
               |  id = ${state.id}
            """.stripMargin
          }.update.run.void
        }
    }

    override def delete(id: Id): ConnectionIO[Unit] =
      sql"""DELETE FROM global_states WHERE type = $tpe AND id = $id""".stripMargin.update.run.void

    override def get(id: Id): IO[Option[S]] =
      sql"""SELECT value FROM global_states WHERE type = $tpe AND id = $id"""
        .query[S]
        .option
        .transact(xas.readCE)

    private def states(offset: Offset, strategy: RefreshStrategy): EnvelopeStream[S] =
      StreamingQuery[Envelope[S]](
        offset,
        offset => sql"""SELECT type, id, value, rev, instant, ordering FROM public.global_states
                       |${Fragments.whereAndOpt(Some(fr"type = $tpe"), offset.asFragment)}
                       |ORDER BY ordering
                       |LIMIT ${config.batchSize}""".stripMargin.query[Envelope[S]],
        _.offset,
        config.copy(refreshStrategy = strategy),
        xas
      )

    override def currentStates(offset: Offset): EnvelopeStream[S] = states(offset, RefreshStrategy.Stop)

    override def states(offset: Offset): EnvelopeStream[S] = states(offset, config.refreshStrategy)
  }

}
