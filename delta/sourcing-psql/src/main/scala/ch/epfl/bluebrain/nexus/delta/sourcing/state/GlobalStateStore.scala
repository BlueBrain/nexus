package ch.epfl.bluebrain.nexus.delta.sourcing.state

import cats.effect.IO
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sourcing.{Serializer, Transactors}
import ch.epfl.bluebrain.nexus.delta.sourcing.config.QueryConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.implicits._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.EntityType
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.query.{RefreshStrategy, StreamingQuery}
import ch.epfl.bluebrain.nexus.delta.sourcing.state.State.GlobalState
import doobie._
import doobie.syntax.all._
import doobie.postgres.implicits._
import doobie.util.transactor.Transactor
import fs2.Stream

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
    * Returns the latest state from the write nodes to get a stronger consistency when the Postgres works in a
    * replicated fashion
    */
  def getWrite(id: Id): IO[Option[S]]

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
  def currentStates(offset: Offset): Stream[IO, S]
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
    implicit val putId: Put[Id]   = serializer.putId
    implicit val getValue: Get[S] = serializer.getValue
    implicit val putValue: Put[S] = serializer.putValue

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

    override def getWrite(id: Id): IO[Option[S]] = get(id, xas.write)

    override def get(id: Id): IO[Option[S]] = get(id, xas.read)

    private def get(id: Id, xa: Transactor[IO]) = GlobalStateGet[Id, S](tpe, id).transact(xa)

    private def states(offset: Offset, strategy: RefreshStrategy): Stream[IO, S] =
      StreamingQuery[(S, Long)](
        offset,
        offset => sql"""SELECT value, ordering FROM public.global_states
                       |${Fragments.whereAndOpt(Some(fr"type = $tpe"), offset.asFragment)}
                       |ORDER BY ordering
                       |LIMIT ${config.batchSize}""".stripMargin.query[(S, Long)],
        { case (_, offset: Long) => Offset.at(offset) },
        config.copy(refreshStrategy = strategy),
        xas
      ).map { case (value, _) => value }

    override def currentStates(offset: Offset): Stream[IO, S]                    = states(offset, RefreshStrategy.Stop)
  }

}
