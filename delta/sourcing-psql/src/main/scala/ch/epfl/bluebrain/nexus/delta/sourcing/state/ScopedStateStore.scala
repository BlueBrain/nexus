package ch.epfl.bluebrain.nexus.delta.sourcing.state

import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.sourcing.EntityDefinition.Serializer
import ch.epfl.bluebrain.nexus.delta.sourcing.Transactors
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.Latest
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EntityType, ProjectRef, Tag}
import ch.epfl.bluebrain.nexus.delta.sourcing.state.State.ScopedState
import doobie._
import doobie.implicits._
import doobie.postgres.circe.jsonb.implicits._
import doobie.postgres.implicits._
import io.circe.Json
import io.circe.syntax.EncoderOps
import monix.bio.{Task, UIO}

import scala.annotation.nowarn

trait ScopedStateStore[Id, S <: ScopedState] {

  /**
    * Persist the state as latest
    */
  def save(state: S): ConnectionIO[Unit] = save(state, Latest)

  /**
    * Persist the state with the given tag
    */
  def save(state: S, tag: Tag): ConnectionIO[Unit]

  /**
    * Delete the state for the given tag
    */
  def delete(ref: ProjectRef, id: Id, tag: Tag): ConnectionIO[Unit]

  /**
    * Returns the latest state
    */
  def get(ref: ProjectRef, id: Id): UIO[Option[S]] = get(ref, id, Latest)

  /**
    * Returns the latest state
    */
  def get(ref: ProjectRef, id: Id, tag: Tag): UIO[Option[S]]

}

object ScopedStateStore {

  def apply[Id, S <: ScopedState](tpe: EntityType, serializer: Serializer[Id, S], xas: Transactors)(implicit
      @nowarn("cat=unused") get: Get[Id],
      put: Put[Id]
  ): ScopedStateStore[Id, S] = new ScopedStateStore[Id, S] {
    import serializer._

    override def save(state: S, tag: Tag): doobie.ConnectionIO[Unit] = {
      val id = extractId(state)
      sql"SELECT 1 FROM scoped_states WHERE type = $tpe AND org = ${state.organization} AND project = ${state.project.project}  AND id = $id AND tag = $tag"
        .query[Int]
        .option
        .flatMap {
          _.fold(sql"""
                      | INSERT INTO scoped_states (
                      |  type,
                      |  org,
                      |  project,
                      |  id,
                      |  tag,
                      |  rev,
                      |  value,
                      |  instant
                      | )
                      | VALUES (
                      |  $tpe,
                      |  ${state.organization},
                      |  ${state.project.project},
                      |  $id,
                      |  $tag,
                      |  ${state.rev},
                      |  ${state.asJson},
                      |  ${state.updatedAt}
                      | )
            """.stripMargin) { _ =>
            sql"""
                 | UPDATE scoped_states SET
                 |  rev = ${state.rev},
                 |  value = ${state.asJson},
                 |  instant = ${state.updatedAt},
                 |  ordering = (select nextval('scoped_states_ordering_seq'))
                 | WHERE
                 |  type = $tpe AND
                 |  org = ${state.organization} AND
                 |  project = ${state.project.project} AND
                 |  id = $id AND
                 |  tag = $tag
            """.stripMargin
          }.update.run.void
        }
    }

    override def delete(ref: ProjectRef, id: Id, tag: Tag): doobie.ConnectionIO[Unit] =
      sql"""DELETE FROM scoped_states WHERE type = $tpe AND org = ${ref.organization} AND project = ${ref.project}  AND id = $id AND tag = $tag""".stripMargin.update.run.void

    override def get(ref: ProjectRef, id: Id, tag: Tag): UIO[Option[S]] =
      sql"""SELECT value FROM scoped_states WHERE type = $tpe AND org = ${ref.organization} AND project = ${ref.project}  AND id = $id AND tag = $tag"""
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
