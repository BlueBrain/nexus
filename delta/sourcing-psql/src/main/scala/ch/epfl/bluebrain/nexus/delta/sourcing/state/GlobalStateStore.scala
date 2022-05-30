package ch.epfl.bluebrain.nexus.delta.sourcing.state

import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.sourcing.EntityDefinition.Serializer
import ch.epfl.bluebrain.nexus.delta.sourcing.Transactors
import ch.epfl.bluebrain.nexus.delta.sourcing.model.EntityType
import ch.epfl.bluebrain.nexus.delta.sourcing.state.State.GlobalState
import doobie._
import doobie.implicits._
import doobie.postgres.circe.jsonb.implicits._
import doobie.postgres.implicits._
import io.circe.Json
import io.circe.syntax.EncoderOps
import monix.bio.{Task, UIO}

import scala.annotation.nowarn

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

}

object GlobalStateStore {

  def apply[Id, S <: GlobalState](
      tpe: EntityType,
      serializer: Serializer[Id, S],
      xas: Transactors
  )(implicit
      @nowarn("cat=unused") get: Get[Id],
      put: Put[Id]
  ): GlobalStateStore[Id, S] = new GlobalStateStore[Id, S] {

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
  }

}
